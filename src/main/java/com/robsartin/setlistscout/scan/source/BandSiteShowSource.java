package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandSiteScraperService;
import com.robsartin.setlistscout.scan.Show;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * The artist's official site (scraped for tour dates) behind the {@link ShowSource} port. Query-only:
 * the orchestrator resolves + caches the site URL and passes it in {@link ScanQuery#officialSiteUrl()}.
 * v1 filters scraped shows by a loose city-name match to the owner's location (precise per-show distance
 * filtering is deferred -- see #28).
 */
@Component
@Order(3)
public class BandSiteShowSource implements ShowSource {

    private final BandSiteScraperService scraper;

    public BandSiteShowSource(BandSiteScraperService scraper) {
        this.scraper = scraper;
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
        List<Show> shows = scraper.scrapeShows(q.artistName(), q.officialSiteUrl(),
                q.windowStart(), q.windowEnd());
        if (q.city() == null) {
            return shows;
        }
        return shows.stream()
                .filter(s -> s.getVenueCity() != null && s.getVenueCity().equalsIgnoreCase(q.city()))
                .toList();
    }
}
