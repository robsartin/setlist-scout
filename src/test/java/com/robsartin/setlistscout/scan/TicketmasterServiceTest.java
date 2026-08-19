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
import static org.assertj.core.api.Assertions.tuple;

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

    // ---- #202: comedy alongside music -----------------------------------------------------
    //
    // One broader query, not one call per classification: see the "one broader query, not one
    // call per classification" comment in TicketmasterService#searchShows for the full reasoning
    // (doubling ~6,400 scan jobs' worth of calls against a rate-limited free-tier key). This
    // test proves that choice at the request level -- the old classificationName=music
    // restriction that excluded comedy results is gone, and nothing has replaced it with a
    // second restriction -- while the tests below prove labeling is read from each event's own
    // response data, never assumed from the query.

    @Test
    @DisplayName("#202: should not restrict the search by classificationName, so comedy events aren't excluded at the query level")
    void queryIsNotRestrictedByClassificationName() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));

        service.searchShows("Aziz Ansari", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).doesNotContain("classificationName");
    }

    @Test
    @DisplayName("#202: should label a Music-segment event as music")
    void labelsMusicSegmentEventAsMusic() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                            "_embedded": {"attractions": [{"name": "Dawes"}]},
                            "classifications": [{"primary": true,
                              "segment": {"name": "Music"}, "genre": {"name": "Rock"}}]
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Dawes", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getKind()).isEqualTo(Show.Kind.MUSIC);
    }

    @Test
    @DisplayName("#202: should label an Arts & Theatre/Comedy-genre event as comedy")
    void labelsComedyGenreEventAsComedy() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                            "_embedded": {"attractions": [{"name": "Aziz Ansari"}]},
                            "classifications": [{"primary": true,
                              "segment": {"name": "Arts & Theatre"}, "genre": {"name": "Comedy"}}]
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Aziz Ansari", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getKind()).isEqualTo(Show.Kind.COMEDY);
    }

    @Test
    @DisplayName("#202: label comes from each event's own classification, not the query -- a crossover artist can appear under either in the same response")
    void labelsEachEventFromItsOwnClassificationIndependently() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                            "_embedded": {
                              "venues": [{"name": "Moody Center", "city": {"name": "Austin"}}],
                              "attractions": [{"name": "Bo Burnham"}]
                            },
                            "classifications": [{"primary": true,
                              "segment": {"name": "Arts & Theatre"}, "genre": {"name": "Comedy"}}]
                          },
                          {
                            "dates": {"start": {"dateTime": "2026-09-02T19:00:00Z"}},
                            "_embedded": {
                              "venues": [{"name": "ACL Live", "city": {"name": "Austin"}}],
                              "attractions": [{"name": "Bo Burnham"}]
                            },
                            "classifications": [{"primary": true,
                              "segment": {"name": "Music"}, "genre": {"name": "Rock"}}]
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Bo Burnham", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(2);
        assertThat(shows).extracting(Show::getVenueName, Show::getKind)
                .containsExactlyInAnyOrder(
                        tuple("Moody Center", Show.Kind.COMEDY),
                        tuple("ACL Live", Show.Kind.MUSIC));
    }

    @Test
    @DisplayName("#202: should drop events classified outside music/comedy that the broader query can return, e.g. Film")
    void dropsEventsClassifiedOutsideMusicOrComedy() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                            "_embedded": {"attractions": [{"name": "Weird Al"}]},
                            "classifications": [{"primary": true,
                              "segment": {"name": "Film"}, "genre": {"name": "Documentary"}}]
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Weird Al", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows)
                .as("Film is split out to #204 and is explicitly out of scope for #202")
                .isEmpty();
    }

    @Test
    @DisplayName("#202: should default to music when the response has no classifications array")
    void defaultsToMusicWhenClassificationsMissing() {
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
        assertThat(shows.get(0).getKind()).isEqualTo(Show.Kind.MUSIC);
    }
}
