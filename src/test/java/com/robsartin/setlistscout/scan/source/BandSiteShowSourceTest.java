package com.robsartin.setlistscout.scan.source;

import com.robsartin.setlistscout.scan.BandSiteScraperService;
import com.robsartin.setlistscout.scan.Show;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BandSiteShowSourceTest {
    private final BandSiteScraperService scraper = mock(BandSiteScraperService.class);
    private final BandSiteShowSource source = new BandSiteShowSource(scraper);
    private final LocalDateTime start = LocalDateTime.now();
    private final LocalDateTime end = start.plusMonths(6);

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
        ScanQuery q = new ScanQuery("ZZ Top", null, "78701", 30.26, -97.74, 50, "Austin", start, end);
        assertThat(source.search(q)).isEmpty();
        verify(scraper, never()).scrapeShows(any(), any(), any(), any());
    }

    @Test
    void filtersScrapedShowsToCityWhenCityPresent() {
        Show austin = showInCity("Austin");
        Show dallas = showInCity("Dallas");
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end))
                .thenReturn(List.of(austin, dallas));
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", 30.26, -97.74, 50, "Austin", start, end);

        assertThat(source.search(q)).containsExactly(austin);
    }

    @Test
    void nullCityReturnsAllScraped() {
        Show a = showInCity("Austin");
        Show b = showInCity("Dallas");
        when(scraper.scrapeShows("ZZ Top", "https://zztop.com", start, end)).thenReturn(List.of(a, b));
        ScanQuery q = new ScanQuery("ZZ Top", "https://zztop.com", "78701", 30.26, -97.74, 50, null, start, end);

        assertThat(source.search(q)).containsExactly(a, b);
    }
}
