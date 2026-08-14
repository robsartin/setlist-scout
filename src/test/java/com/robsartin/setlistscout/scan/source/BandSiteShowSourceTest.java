package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandSiteScraperService;
import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.settings.GeocodingService;
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
}
