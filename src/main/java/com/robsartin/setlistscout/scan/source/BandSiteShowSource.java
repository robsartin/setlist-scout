package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandSiteScraperService;
import com.robsartin.setlistscout.scan.GeoDistance;
import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.settings.GeocodingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The artist's official site (scraped for tour dates) behind the {@link ShowSource} port. Query-only:
 * the orchestrator resolves + caches the site URL and passes it in {@link ScanQuery#officialSiteUrl()}.
 * Filters scraped shows by real distance (#28): each show's venue city is geocoded (assuming the
 * owner's settings state -- band-site shows carry only a bare city string, no state) and kept when
 * within {@link ScanQuery#radiusMiles()} of the owner's coordinates, the same Haversine check
 * {@link BandsintownShowSource} uses. A show that can't be geocoded (city not found, or the owner/show
 * has no usable location) falls back to the original loose city-name match rather than being dropped --
 * a show that would have matched before must not silently disappear because geocoding failed.
 * <p>
 * Known gap: assuming the owner's state means a show just across a state line in a metro that spans
 * one (Kansas City, Texarkana, ...) will 404 on the city+state lookup and fall back to the old
 * city-name match -- never worse than before #28, just not improved for that case.
 * <p>
 * On by default ({@code matchIfMissing = true}) -- {@code setlistscout.sources.band-site=false}
 * (Render env var {@code SETLISTSCOUT_SOURCES_BANDSITE=false}) opts this source out with zero
 * effect on the other 7 (issue #139).
 */
@Component
@Order(3)
@ConditionalOnProperty(name = "setlistscout.sources.band-site", havingValue = "true", matchIfMissing = true)
public class BandSiteShowSource implements ShowSource {

    private final BandSiteScraperService scraper;
    private final GeocodingService geocoder;

    public BandSiteShowSource(BandSiteScraperService scraper, GeocodingService geocoder) {
        this.scraper = scraper;
        this.geocoder = geocoder;
    }

    @Override
    public String id() {
        return "band-site";
    }

    @Override
    public List<Show> search(ScanQuery q) {
        if (q.officialSiteUrl() == null) {
            return List.of();
        }
        List<Show> scraped = scraper.scrapeShows(q.artistName(), q.officialSiteUrl(),
                q.windowStart(), q.windowEnd());
        List<Show> shows = scraped.stream().map(s -> applyDefaultVenue(s, q)).filter(Objects::nonNull).toList();
        if (q.city() == null) {
            return shows;
        }
        return shows.stream().filter(s -> withinRange(s, q)).toList();
    }

    /**
     * #218: some band-site pages (e.g. ASO's own season-announcement page) name no venue anywhere,
     * so the extractor hands back a show with a blank venue name -- see {@link
     * com.robsartin.setlistscout.scan.TourPageLlmService}'s prompt and its {@code !venue.isBlank()}
     * removal. Substitute the artist's configured default venue name AND city as a pair when that
     * happens; a venue the page actually stated is never overridden (checked here, before any
     * distance filtering, so a defaulted city is available for {@link #withinRange} same as a real
     * one). {@code Show} has no setters for venueName/venueCity, so a new instance carries the rest
     * of the original show's fields through unchanged.
     * <p>
     * Returns {@code null} (filtered out by the caller) when the show has no venue of its own AND
     * the artist has no default configured -- exactly today's behaviour (the show never makes it
     * this far to begin with, pre-#218): no crash, no invented placeholder venue.
     */
    private Show applyDefaultVenue(Show s, ScanQuery q) {
        if (s.getVenueName() != null && !s.getVenueName().isBlank()) {
            return s;
        }
        if (q.defaultVenueName() == null || q.defaultVenueName().isBlank()) {
            return null;
        }
        return new Show(s.getArtistName(), s.getEventDateTime(), q.defaultVenueName(), q.defaultVenueCity(),
                s.getPrice(), s.getSource(), s.getTicketUrl(), s.getKind());
    }

    private boolean withinRange(Show s, ScanQuery q) {
        if (q.latitude() != null && q.longitude() != null && q.state() != null && s.getVenueCity() != null) {
            Optional<GeocodingService.GeoResult> geo = geocoder.geocodeCity(s.getVenueCity(), q.state());
            if (geo.isPresent()) {
                double miles = GeoDistance.milesBetween(q.latitude(), q.longitude(),
                        geo.get().latitude(), geo.get().longitude());
                return miles <= q.radiusMiles();
            }
        }
        // No coordinates to compare (owner ungeocoded, show has no city, or the city couldn't be
        // geocoded) -- fall back to the original city-name match so the show isn't silently dropped.
        return s.getVenueCity() != null && s.getVenueCity().equalsIgnoreCase(q.city());
    }
}
