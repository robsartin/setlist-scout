package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandSiteScraperService;
import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.settings.GeocodingService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BandSiteShowSourceTest {
    private final BandSiteScraperService scraper = mock(BandSiteScraperService.class);
    private final GeocodingService geocoder = mock(GeocodingService.class);
    private final BandSiteShowSource source = new BandSiteShowSource(scraper, geocoder);
    private final LocalDateTime start = LocalDateTime.now();
    private final LocalDateTime end = start.plusMonths(6);

    // Austin, TX -- the default owner location used across these tests.
    private static final double OWNER_LAT = 30.2672;
    private static final double OWNER_LON = -97.7431;

    private Show showInCity(String city) {
        Show s = mock(Show.class);
        // #218: BandSiteShowSource now applies the artist's configured default venue when a show's
        // own venue NAME is blank/null. Stub a realistic non-blank name here so these pre-existing
        // city-only tests (none of which configure a default) are unaffected by that new step --
        // matching production reality, where every show reaching this filter already carried some
        // venue name (the LLM extractor used to drop a blank-venue row outright, before #218).
        when(s.getVenueName()).thenReturn("Some Venue");
        when(s.getVenueCity()).thenReturn(city);
        return s;
    }

    @Test
    void idIsBandSite() {
        assertThat(source.id()).isEqualTo("band-site");
    }

    @Test
    void nullUrlSkipsScrapeAndReturnsEmpty() {
        ScanQuery q = new ScanQuery("ZZ Top", null, "78701", OWNER_LAT, OWNER_LON, 50, "Austin", "TX", start, end);
        assertThat(source.search(q)).isEmpty();
        verify(scraper, never()).scrapeShows(any(), any(), any(), any());
    }

    @Test
    void nullCityReturnsAllScrapedWithoutGeocoding() {
        Show a = showInCity("Austin");
        Show b = showInCity("Dallas");
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end)).thenReturn(List.of(a, b));
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", OWNER_LAT, OWNER_LON, 50,
                null, "TX", start, end);

        assertThat(source.search(q)).containsExactly(a, b);
        verify(geocoder, never()).geocodeCity(any(), any());
    }

    @Test
    void keepsAGeocodedShowInsideTheRadiusEvenWithADifferentCityName() {
        // Round Rock is ~18mi from Austin -- a real suburb the old city-string match would drop.
        Show roundRock = showInCity("Round Rock");
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end)).thenReturn(List.of(roundRock));
        when(geocoder.geocodeCity("Round Rock", "TX"))
                .thenReturn(Optional.of(new GeocodingService.GeoResult(30.5083, -97.6789, "Round Rock", "TX")));
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", OWNER_LAT, OWNER_LON, 50,
                "Austin", "TX", start, end);

        assertThat(source.search(q)).containsExactly(roundRock);
    }

    @Test
    void dropsAGeocodedShowOutsideTheRadius() {
        // Dallas is ~200mi from Austin -- well outside a 50mi radius.
        Show dallas = showInCity("Dallas");
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end)).thenReturn(List.of(dallas));
        when(geocoder.geocodeCity("Dallas", "TX"))
                .thenReturn(Optional.of(new GeocodingService.GeoResult(32.7767, -96.7970, "Dallas", "TX")));
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", OWNER_LAT, OWNER_LON, 50,
                "Austin", "TX", start, end);

        assertThat(source.search(q)).isEmpty();
    }

    @Test
    void fallsBackToCityMatchWhenGeocodeFails() {
        Show austin = showInCity("Austin");
        Show dallas = showInCity("Dallas");
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end)).thenReturn(List.of(austin, dallas));
        when(geocoder.geocodeCity(any(), any())).thenReturn(Optional.empty());
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", OWNER_LAT, OWNER_LON, 50,
                "Austin", "TX", start, end);

        assertThat(source.search(q)).containsExactly(austin);
    }

    @Test
    void fallsBackToCityMatchWhenOwnerCoordinatesAreMissing() {
        Show austin = showInCity("Austin");
        Show dallas = showInCity("Dallas");
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end)).thenReturn(List.of(austin, dallas));
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", null, null, 50,
                "Austin", "TX", start, end);

        assertThat(source.search(q)).containsExactly(austin);
        verify(geocoder, never()).geocodeCity(any(), any());
    }

    @Test
    void fallsBackToCityMatchWhenShowHasNoVenueCity() {
        Show noCity = showInCity(null);
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end)).thenReturn(List.of(noCity));
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", OWNER_LAT, OWNER_LON, 50,
                "Austin", "TX", start, end);

        assertThat(source.search(q)).isEmpty();
        verify(geocoder, never()).geocodeCity(any(), any());
    }

    // ---- #218: per-artist default venue -- ASO's own season-announcement page names no hall -----
    // anywhere, so the extracted show has no venue of its own. Real (non-mocked) Show instances are
    // used below, not the showInCity() mock helper, since applyDefaultVenue reconstructs a Show from
    // every one of its fields (venueName/venueCity have no setters), and a realistic ExtractedShow
    // from TourPageLlmService now hands BandSiteScraperService a BLANK ("", trimmed), not null,
    // venue string when the page states none -- see TourPageLlmServiceTest#blankVenueLineIsNotDropped.

    private static final String ASO_URL = "https://austinsymphony.org/season-announcement/";

    @Test
    @DisplayName("a show with no venue of its own takes the artist's default venue NAME and CITY (#218)")
    void showWithNoVenueTakesTheArtistDefault() {
        Show noVenue = new Show("Austin Symphony Orchestra", start.plusDays(10), "", "",
                null, "band-site:austinsymphony.org", ASO_URL, Show.Kind.MUSIC);
        when(scraper.scrapeShows("Austin Symphony Orchestra", ASO_URL, start, end)).thenReturn(List.of(noVenue));
        // city=null isolates the defaulting step from withinRange's distance filter (covered
        // end-to-end, on purpose, by defaultedShowSurvivesTheRealDistanceFilter below).
        ScanQuery q = new ScanQuery("Austin Symphony Orchestra", ASO_URL,
                "Long Center for the Performing Arts", "Austin",
                "78701", OWNER_LAT, OWNER_LON, 50, null, "TX", start, end);

        List<Show> result = source.search(q);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVenueName()).isEqualTo("Long Center for the Performing Arts");
        assertThat(result.get(0).getVenueCity()).isEqualTo("Austin");
    }

    @Test
    @DisplayName("a show whose page states its own venue keeps it -- the artist's default never "
            + "overrides (#218)")
    void showWithItsOwnVenueIsNeverOverridden() {
        // A DIFFERENT city than the configured default, deliberately: if the code wrongly applied
        // the default this assertion would catch it (a same-city default could pass by coincidence).
        Show ownVenue = new Show("Austin Symphony Orchestra", start.plusDays(10), "Moody Center", "Round Rock",
                null, "band-site:austinsymphony.org", ASO_URL, Show.Kind.MUSIC);
        when(scraper.scrapeShows("Austin Symphony Orchestra", ASO_URL, start, end)).thenReturn(List.of(ownVenue));
        ScanQuery q = new ScanQuery("Austin Symphony Orchestra", ASO_URL,
                "Long Center for the Performing Arts", "Austin",
                "78701", OWNER_LAT, OWNER_LON, 50, null, "TX", start, end);

        List<Show> result = source.search(q);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVenueName()).isEqualTo("Moody Center");
        assertThat(result.get(0).getVenueCity()).isEqualTo("Round Rock");
    }

    @Test
    @DisplayName("an artist with no configured default and a show with no venue behaves exactly as "
            + "today -- dropped, no crash, no invented venue (#218)")
    void noVenueAndNoDefaultBehavesAsToday() {
        Show noVenue = new Show("Some Band", start.plusDays(10), "", "",
                null, "band-site:someband.com", "https://someband.com", Show.Kind.MUSIC);
        when(scraper.scrapeShows("Some Band", "https://someband.com", start, end)).thenReturn(List.of(noVenue));
        // 10-arg convenience constructor -- no default configured, same as every artist today.
        ScanQuery q = new ScanQuery("Some Band", "https://someband.com", "78701", OWNER_LAT, OWNER_LON, 50,
                null, "TX", start, end);

        assertThat(source.search(q)).isEmpty();
    }

    @Test
    @DisplayName("end-to-end: a show defaulted to the artist's venue survives withinRange's REAL "
            + "distance filter (#218) -- the regression this feature exists to guard against. A "
            + "default that supplied only a venue NAME would leave venueCity null, and withinRange "
            + "drops a null-city show via the same fallback comparison that's also false for null -- "
            + "so the show would extract successfully and then silently vanish here, exactly like "
            + "#211: green scan, zero output.")
    void defaultedShowSurvivesTheRealDistanceFilter() {
        Show noVenue = new Show("Austin Symphony Orchestra", start.plusDays(10), "", "",
                null, "band-site:austinsymphony.org", ASO_URL, Show.Kind.MUSIC);
        when(scraper.scrapeShows("Austin Symphony Orchestra", ASO_URL, start, end)).thenReturn(List.of(noVenue));
        when(geocoder.geocodeCity("Austin", "TX"))
                .thenReturn(Optional.of(new GeocodingService.GeoResult(OWNER_LAT, OWNER_LON, "Austin", "TX")));
        ScanQuery q = new ScanQuery("Austin Symphony Orchestra", ASO_URL,
                "Long Center for the Performing Arts", "Austin",
                "78701", OWNER_LAT, OWNER_LON, 50, "Austin", "TX", start, end);

        List<Show> result = source.search(q);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getVenueCity()).isEqualTo("Austin");
    }
}
