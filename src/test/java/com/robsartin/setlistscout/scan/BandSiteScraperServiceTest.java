package com.robsartin.setlistscout.scan;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.robsartin.setlistscout.service.LogCapture;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
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
    @DisplayName("on a tour-page fetch failure, logs the tour-page URL that actually failed, not the front page")
    void logsTheUrlThatActuallyFailedNotAlwaysTheFrontPage() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            // Front page: fetch succeeds (200) but has no JSON-LD events, and links to a tour page.
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "text/html")
                    .setBody("<html><body><a href=\"/tour\">Tour Dates</a></body></html>"));
            // Tour page: this second fetch is the one that fails.
            server.enqueue(new MockResponse().setResponseCode(500));
            String siteUrl = server.url("/").toString();
            String tourUrl = server.url("/tour").toString();

            try (LogCapture logs = LogCapture.attach(BandSiteScraperService.class)) {
                service.scrapeShows("Dawes", siteUrl, START, END);

                ILoggingEvent warnEvent = logs.events().stream()
                        .filter(e -> e.getLevel() == Level.WARN)
                        .findFirst()
                        .orElseThrow(() -> new AssertionError("expected a WARN log for the failed fetch"));
                String loggedUrl = warnEvent.getKeyValuePairs().stream()
                        .filter(kv -> "url".equals(kv.key))
                        .findFirst()
                        .map(kv -> String.valueOf(kv.value))
                        .orElseThrow(() -> new AssertionError("expected a 'url' key-value on the WARN log"));

                assertThat(loggedUrl).isEqualTo(tourUrl);
            }
        }
    }

    @Test
    @DisplayName("logs a DEBUG line with cause for a malformed JSON-LD block, and still picks up other blocks")
    void logsDebugOnMalformedJsonLd() {
        String html = """
                <html><head>
                <script type="application/ld+json">{ this is not valid json </script>
                <script type="application/ld+json">
                {"@type":"MusicEvent","startDate":"2026-06-01","location":{"name":"Moody Center"}}
                </script>
                </head><body></body></html>
                """;
        Document doc = Jsoup.parse(html, "https://band.com");

        try (LogCapture logs = LogCapture.attachAt(BandSiteScraperService.class, Level.DEBUG)) {
            List<Show> shows = service.extractShows("Dawes", doc, "https://band.com", START, END);

            assertThat(shows).hasSize(1); // the well-formed block after the malformed one still comes through

            ILoggingEvent debugEvent = logs.events().stream()
                    .filter(e -> e.getLevel() == Level.DEBUG)
                    .filter(e -> e.getThrowableProxy() != null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "expected a DEBUG log with a cause for the malformed ld+json block"));
            assertThat(debugEvent.getThrowableProxy().getClassName()).isNotBlank();
        }
    }

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
                new TourPageLlmService.ExtractedShow(
                        LocalDate.of(2026, 7, 4), "The Fillmore", "San Francisco", null, Show.Kind.MUSIC)));

        List<Show> shows = service.extractShows("Dawes", doc, "https://band.com", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getVenueName()).isEqualTo("The Fillmore");
        assertThat(shows.get(0).getVenueCity()).isEqualTo("San Francisco");
        assertThat(shows.get(0).getSource()).isEqualTo("band-site:band.com");
    }

    @Test
    @DisplayName("LLM extraction: a performer name and COMEDY kind override the tracked artist and default kind (#208)")
    void llmExtractionUsesPerformerAndComedyKind() {
        String html = "<html><body><h1>Shows</h1><p>Jul 4 - Some Comedian</p></body></html>";
        Document doc = Jsoup.parse(html, "https://capcitycomedy.com");
        when(tourPageLlm.extractShows(eq("Cap City Comedy Club"), anyString())).thenReturn(List.of(
                new TourPageLlmService.ExtractedShow(
                        LocalDate.of(2026, 7, 4), "Cap City Comedy Club", "Austin", "Some Comedian", Show.Kind.COMEDY)));

        List<Show> shows = service.extractShows(
                "Cap City Comedy Club", doc, "https://capcitycomedy.com", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Some Comedian");
        assertThat(shows.get(0).getKind()).isEqualTo(Show.Kind.COMEDY);
    }

    @Test
    @DisplayName("LLM extraction: an explicit MUSIC kind is honored, on a venue calendar where the "
            + "performer must still override the tracked (venue) name (#208)")
    void llmExtractionUsesMusicKindAndPerformerName() {
        // Deliberately a venue-calendar shape (tracked name != performer) so this test cannot pass
        // under the pre-#208 hardcoded "always tracked name" fallback: MUSIC alone is not
        // discriminating since it's also the old hardcoded default, but the performer assertion is.
        String html = "<html><body><h1>Calendar</h1><p>Jul 4 - Indie Rock Trio</p></body></html>";
        Document doc = Jsoup.parse(html, "https://thevenuedowntown.com");
        when(tourPageLlm.extractShows(eq("The Venue Downtown"), anyString())).thenReturn(List.of(
                new TourPageLlmService.ExtractedShow(LocalDate.of(2026, 7, 4), "The Venue Downtown",
                        "Austin", "Indie Rock Trio", Show.Kind.MUSIC)));

        List<Show> shows = service.extractShows(
                "The Venue Downtown", doc, "https://thevenuedowntown.com", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Indie Rock Trio");
        assertThat(shows.get(0).getKind()).isEqualTo(Show.Kind.MUSIC);
    }

    @Test
    @DisplayName("LLM extraction: a null performer (3-field backward-compat response) falls back to the "
            + "tracked artist name (#208)")
    void llmExtractionFallsBackToTrackedArtistWhenPerformerNull() {
        String html = "<html><body><h1>Tour dates</h1><p>Jul 4 - The Fillmore</p></body></html>";
        Document doc = Jsoup.parse(html, "https://band.com");
        when(tourPageLlm.extractShows(eq("Dawes"), anyString())).thenReturn(List.of(
                new TourPageLlmService.ExtractedShow(
                        LocalDate.of(2026, 7, 4), "The Fillmore", "San Francisco", null, Show.Kind.MUSIC)));

        List<Show> shows = service.extractShows("Dawes", doc, "https://band.com", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Dawes");
        assertThat(shows.get(0).getKind()).isEqualTo(Show.Kind.MUSIC);
    }

    @Test
    @DisplayName("LLM extraction: a blank (non-null) performer field falls back to the tracked artist name (#208)")
    void llmExtractionFallsBackToTrackedArtistWhenPerformerBlank() {
        String html = "<html><body><h1>Tour dates</h1><p>Jul 4 - The Fillmore</p></body></html>";
        Document doc = Jsoup.parse(html, "https://band.com");
        when(tourPageLlm.extractShows(eq("Dawes"), anyString())).thenReturn(List.of(
                new TourPageLlmService.ExtractedShow(
                        LocalDate.of(2026, 7, 4), "The Fillmore", "San Francisco", "   ", Show.Kind.MUSIC)));

        List<Show> shows = service.extractShows("Dawes", doc, "https://band.com", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Dawes");
    }

    @Test
    @DisplayName("JSON-LD: uses the performer name as the show's artist when present (#208)")
    void jsonLdUsesPerformerNameWhenPresent() {
        String html = """
                <html><head>
                <script type="application/ld+json">
                {"@type":"Event","startDate":"2026-06-01","location":{"name":"Cap City Comedy Club"},
                 "performer":{"@type":"Person","name":"Some Comedian"}}
                </script>
                </head><body></body></html>
                """;
        Document doc = Jsoup.parse(html, "https://capcitycomedy.com");

        List<Show> shows = service.extractShows(
                "Cap City Comedy Club", doc, "https://capcitycomedy.com", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Some Comedian");
    }

    @Test
    @DisplayName("JSON-LD: falls back to the tracked artist name when there is no performer (existing "
            + "behaviour, must not regress) (#208)")
    void jsonLdFallsBackToTrackedArtistWhenNoPerformer() {
        String html = """
                <html><head>
                <script type="application/ld+json">
                {"@type":"MusicEvent","startDate":"2026-06-01","location":{"name":"Moody Center"}}
                </script>
                </head><body></body></html>
                """;
        Document doc = Jsoup.parse(html, "https://dawestheband.com");

        List<Show> shows = service.extractShows("Dawes", doc, "https://dawestheband.com", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Dawes");
    }
}
