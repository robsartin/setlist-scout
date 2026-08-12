package com.robsartin.setlistscout.scan;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class BandSiteScraperServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 12, 31, 0, 0);

    private final TourPageLlmService tourPageLlm = mock(TourPageLlmService.class);
    private final BandSiteScraperService service = new BandSiteScraperService(tourPageLlm);

    @Test
    @DisplayName("extracts shows from schema.org Event JSON-LD, tagging the band-site source")
    void extractsJsonLdEvents() {
        String html = """
                <html><head>
                <script type="application/ld+json">
                {"@type":"MusicEvent","startDate":"2026-06-01T20:00:00","name":"Dawes",
                 "location":{"@type":"Place","name":"Moody Center",
                             "address":{"@type":"PostalAddress","addressLocality":"Austin"}}}
                </script>
                </head><body>Tour</body></html>
                """;
        Document doc = Jsoup.parse(html, "https://dawestheband.com");

        List<Show> shows = service.extractShows("Dawes", doc, "https://dawestheband.com", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getVenueName()).isEqualTo("Moody Center");
        assertThat(shows.get(0).getVenueCity()).isEqualTo("Austin");
        assertThat(shows.get(0).getSource()).isEqualTo("band-site:dawestheband.com");
        verifyNoInteractions(tourPageLlm); // JSON-LD found -> no LLM call
    }

    @Test
    @DisplayName("drops JSON-LD events outside the date window")
    void dropsOutOfWindowJsonLd() {
        String html = """
                <html><head>
                <script type="application/ld+json">
                {"@type":"Event","startDate":"2020-06-01","location":{"name":"Old Venue"}}
                </script>
                </head><body></body></html>
                """;
        Document doc = Jsoup.parse(html, "https://band.com");

        assertThat(service.extractShows("Dawes", doc, "https://band.com", START, END)).isEmpty();
    }

    @Test
    @DisplayName("falls back to the LLM when there is no structured JSON-LD")
    void llmFallbackWhenNoJsonLd() {
        String html = "<html><body><h1>Tour dates</h1><p>Jul 4 - The Fillmore</p></body></html>";
        Document doc = Jsoup.parse(html, "https://band.com");
        when(tourPageLlm.extractShows(eq("Dawes"), anyString())).thenReturn(List.of(
                new TourPageLlmService.ExtractedShow(LocalDate.of(2026, 7, 4), "The Fillmore", "San Francisco")));

        List<Show> shows = service.extractShows("Dawes", doc, "https://band.com", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getVenueName()).isEqualTo("The Fillmore");
        assertThat(shows.get(0).getVenueCity()).isEqualTo("San Francisco");
        assertThat(shows.get(0).getSource()).isEqualTo("band-site:band.com");
    }
}
