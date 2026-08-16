package com.robsartin.setlistscout.scan;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.robsartin.setlistscout.service.LogCapture;
import com.robsartin.setlistscout.service.TestAppProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TicketmasterServiceTest {

    private MockWebServer server;
    private TicketmasterService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new TicketmasterService(TestAppProperties.withKeys(), server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("should parse a full event with venue, price, and ticket url")
    void shouldParseFullEvent() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "_embedded": {
                            "events": [
                              {
                                "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                                "_embedded": {
                                  "venues": [{"name": "Moody Center", "city": {"name": "Austin"}}],
                                  "attractions": [{"name": "Dawes"}]
                                },
                                "priceRanges": [{"min": 45.5}],
                                "url": "https://example.com/tickets"
                              }
                            ]
                          }
                        }
                        """));

        List<Show> shows = service.searchShows("Dawes", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
        Show show = shows.get(0);
        assertThat(show.getArtistName()).isEqualTo("Dawes");
        assertThat(show.getVenueName()).isEqualTo("Moody Center");
        assertThat(show.getVenueCity()).isEqualTo("Austin");
        assertThat(show.getPrice()).isEqualByComparingTo("45.5");
        assertThat(show.getTicketUrl()).isEqualTo("https://example.com/tickets");
        assertThat(show.getSource()).isEqualTo("ticketmaster");
    }

    @Test
    @DisplayName("should prefer venue-local date/time over the UTC dateTime")
    void shouldPreferLocalDateTimeOverUtc() {
        // An evening show: localTime 20:00 in the venue's zone, which the UTC
        // dateTime renders as the next-day 01:00 (a different wall-clock value).
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {"dates": {"start": {
                            "localDate": "2026-09-01",
                            "localTime": "20:00:00",
                            "dateTime": "2026-09-02T01:00:00Z"
                          }},
                          "_embedded": {"attractions": [{"name": "Dawes"}]}}
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Dawes", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getEventDateTime())
                .isEqualTo(LocalDateTime.of(2026, 9, 1, 20, 0, 0));
    }

    @Test
    @DisplayName("should return an empty list when _embedded is missing")
    void shouldReturnEmptyWhenNoEmbedded() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));

        List<Show> shows = service.searchShows("Dawes", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("should log a DEBUG count=0 line when _embedded is missing, since that's a legitimate zero-result search")
    void shouldLogDebugCountZeroWhenNoEmbedded() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));

        try (LogCapture logs = LogCapture.attachAt(TicketmasterService.class, Level.DEBUG)) {
            List<Show> shows = service.searchShows("Dawes", "78701", null, null, 50,
                    LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

            assertThat(shows).isEmpty();

            ILoggingEvent debugEvent = logs.events().stream()
                    .filter(e -> e.getLevel() == Level.DEBUG)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "expected a DEBUG log for the zero-result search"));
            String loggedCount = debugEvent.getKeyValuePairs().stream()
                    .filter(kv -> "count".equals(kv.key))
                    .findFirst()
                    .map(kv -> String.valueOf(kv.value))
                    .orElseThrow(() -> new AssertionError(
                            "expected a 'count' key-value on the DEBUG log"));

            assertThat(loggedCount).isEqualTo("0");
        }
    }

    @Test
    @DisplayName("should default venue name and leave price null when absent")
    void shouldDefaultMissingFields() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {"dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                          "_embedded": {"attractions": [{"name": "Dawes"}]}}
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Dawes", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getVenueName()).isEqualTo("Unknown venue");
        assertThat(shows.get(0).getVenueCity()).isNull();
        assertThat(shows.get(0).getPrice()).isNull();
    }

    @Test
    @DisplayName("should return an empty list when the API errors")
    void shouldReturnEmptyOnServerError() {
        server.enqueue(new MockResponse().setResponseCode(500));

        List<Show> shows = service.searchShows("Dawes", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("should keep only events whose attractions include the searched artist")
    void shouldFilterOutEventsWithoutMatchingAttraction() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "_embedded": {
                            "events": [
                              {
                                "dates": {"start": {"dateTime": "2026-10-14T19:00:00Z"}},
                                "_embedded": {
                                  "venues": [{"name": "Emo's", "city": {"name": "Austin"}}],
                                  "attractions": [{"name": "The Best"}]
                                },
                                "url": "https://example.com/the-best-show"
                              },
                              {
                                "dates": {"start": {"dateTime": "2026-10-14T19:00:00Z"}},
                                "_embedded": {
                                  "venues": [{"name": "Emo's", "city": {"name": "Austin"}}],
                                  "attractions": [{"name": "Horse Jumper of Love"}, {"name": "Dead"}]
                                },
                                "url": "https://example.com/horse-jumper-show"
                              }
                            ]
                          }
                        }
                        """));

        List<Show> shows = service.searchShows("the Best", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getTicketUrl()).isEqualTo("https://example.com/the-best-show");
    }

    @Test
    @DisplayName("should drop events with no attractions array, since a match can't be confirmed")
    void shouldFilterOutEventsWithNoAttractionsArray() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "dates": {"start": {"dateTime": "2026-10-14T19:00:00Z"}},
                            "_embedded": {"venues": [{"name": "Emo's", "city": {"name": "Austin"}}]},
                            "url": "https://example.com/no-attractions-show"
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("the Best", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("should label the show with the event's real name, not the search keyword")
    void labelsShowWithEventNameNotSearchKeyword() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "_embedded": {
                            "events": [
                              {
                                "name": "The Damn Torpedoes - Tom Petty Tribute",
                                "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                                "_embedded": {
                                  "venues": [{"name": "Moody Center", "city": {"name": "Austin"}}],
                                  "attractions": [{"name": "Tom Petty"}]
                                }
                              }
                            ]
                          }
                        }
                        """));

        List<Show> shows = service.searchShows("Tom Petty", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("The Damn Torpedoes - Tom Petty Tribute");
    }

    @Test
    @DisplayName("should fall back to the search keyword when the event has no name")
    void fallsBackToKeywordWhenEventNameMissing() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {
                          "_embedded": {
                            "events": [
                              {
                                "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                                "_embedded": {
                                  "venues": [{"name": "Moody Center", "city": {"name": "Austin"}}],
                                  "attractions": [{"name": "Tom Petty"}]
                                }
                              }
                            ]
                          }
                        }
                        """));

        List<Show> shows = service.searchShows("Tom Petty", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Tom Petty");
    }

    @Test
    @DisplayName("should send geoPoint (not postalCode) plus the radius when lat/long are present")
    void shouldSendGeoPointInsteadOfPostalCodeWhenLatLongPresent() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));

        // 43.6311,-71.4997 (Meredith NH) -> geohash drv0hyz98, the exact pairing verified
        // against the live Ticketmaster API while diagnosing #152.
        service.searchShows("Dawes", "78701", 43.6311, -71.4997, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("geoPoint=drv0hyz98");
        assertThat(request.getPath()).contains("radius=50");
        assertThat(request.getPath()).doesNotContain("postalCode");
    }

    @Test
    @DisplayName("should fall back to postalCode plus the radius when lat/long are null")
    void shouldFallBackToPostalCodeWhenLatLongAreNull() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));

        service.searchShows("Dawes", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("postalCode=78701");
        assertThat(request.getPath()).contains("radius=50");
        assertThat(request.getPath()).doesNotContain("geoPoint");
    }
}
