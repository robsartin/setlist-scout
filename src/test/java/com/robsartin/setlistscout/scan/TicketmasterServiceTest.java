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
    // One call, classificationName sent twice (music and comedy): see the comment in
    // TicketmasterService#searchShows for the full reasoning, including a live-verified reason
    // this ISN'T "no classificationName at all" -- that broader-query design has a real
    // page-crowd-out bug for artist names that collide with other segments (Chicago, Boston,
    // Phoenix, Kansas, Europe, Alabama are all real tracked bands), confirmed against the real
    // API before it shipped further. This test proves the fix at the request level -- both
    // values are actually sent, so comedy isn't excluded AND the response is still narrowed
    // server-side, so nothing else can crowd out either -- while the tests below prove labeling
    // is read from each event's own response data, never assumed from the query.

    @Test
    @DisplayName("#202: should send classificationName for both music and comedy, in one call")
    void queryIncludesBothClassificationNames() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));

        service.searchShows("Aziz Ansari", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(server.getRequestCount()).as("one HTTP call, not one per classification").isEqualTo(1);
        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("classificationName=music");
        assertThat(request.getPath()).contains("classificationName=comedy");
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

    // ---- #207: rescue attraction-less events whose title names the artist -----------------
    //
    // hasMatchingAttraction's exact match only fires when Ticketmaster actually linked an
    // attraction; a null OR empty array used to drop the event unconditionally, even when the
    // artist's name is right there in the event title (measured: 19 of 137 returned events
    // across 60 random tracked artists, 13.9%, had empty attractions). The fallback below runs
    // ONLY in that null/empty case -- a populated-but-non-matching array still returns false
    // without ever trying the title fallback (see the "critical regression guard" test below) --
    // and only keeps the event when the artist's name (>= 2 tokens) appears as a consecutive
    // token run in the title AND the title carries no tribute/homage marker. See the Javadoc on
    // TicketmasterService#hasMatchingAttraction for the full rationale, including why
    // featuring/ft is deliberately NOT a marker.

    @Test
    @DisplayName("#207: should rescue an attraction-less event when the artist's name is a consecutive token run in the title")
    void shouldRescueEventWithNoAttractionsWhenArtistNameIsAConsecutiveRunInTheTitle() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "A Very Merry Symphony ft. Austin Symphony Orchestra",
                            "dates": {"start": {"dateTime": "2026-12-20T19:00:00Z"}},
                            "_embedded": {"venues": [{"name": "H-E-B Center at Cedar Park", "city": {"name": "Cedar Park"}}]}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Austin Symphony Orchestra", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("A Very Merry Symphony ft. Austin Symphony Orchestra");
    }

    @Test
    @DisplayName("#207: should rescue an event with an empty attractions array the same way as a missing one")
    void shouldRescueEventWithEmptyAttractionsArrayTheSameAsAMissingOne() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "A Very Merry Symphony ft. Austin Symphony Orchestra",
                            "dates": {"start": {"dateTime": "2026-12-20T19:00:00Z"}},
                            "_embedded": {"attractions": []}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Austin Symphony Orchestra", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
    }

    @Test
    @DisplayName("#207: should rescue an attraction-less event when the artist's tokens match despite adjacent punctuation")
    void shouldRescueEventWhenArtistTokensMatchDespiteAdjacentPunctuation() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "Tommy Emmanuel, CGP - Living In The Light Tour",
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Tommy Emmanuel", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
    }

    @Test
    @DisplayName("#207: should drop an attraction-less event whose title contains 'tribute'")
    void shouldDropAttractionlessEventWhoseTitleContainsTribute() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "Tribute to JIMI HENDRIX by THE HENDRIX EXPERIMENT",
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Jimi Hendrix", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("#207: should drop an attraction-less event whose title contains 'celebration', proving 'featuring' alone is not a marker")
    void shouldDropAttractionlessEventWhoseTitleContainsCelebration() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "Jimi Hendrix Celebration featuring Jeff Moore",
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Jimi Hendrix", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("#207: should drop an attraction-less event whose title contains 'the gospel of'")
    void shouldDropAttractionlessEventWhoseTitleContainsGospelOf() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "Revelations: The Gospel of Chris Cornell",
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Chris Cornell", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("#207: should drop an attraction-less event for a single-token artist name, even when it appears in the title")
    void shouldDropAttractionlessEventForSingleTokenArtistName() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "New Year's Eve in Austin",
                            "dates": {"start": {"dateTime": "2026-12-31T19:00:00Z"}}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Austin", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("#207: should drop an attraction-less event whose title contains 'the music of'")
    void shouldDropAttractionlessEventWhoseTitleContainsTheMusicOf() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "The Rob Dylan Band - Performing The Music of Bob Dylan",
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Bob Dylan", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("#207: should drop an attraction-less event whose title has no consecutive run of the artist's tokens")
    void shouldDropAttractionlessEventWhoseTitleHasNoTokenRunMatch() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "Some Unrelated Show",
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Austin Symphony Orchestra", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("#207: a populated but non-matching attractions array is still dropped, even where the title fallback would not have applied anyway")
    void shouldDropWhenAttractionsArrayIsPopulatedButNonMatching() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "UNDERTALE Symphony",
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                            "_embedded": {"attractions": [{"name": "UNDERTALE Symphony"}]}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Austin Symphony", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("#207: should keep an event via exact match when the populated attractions array matches")
    void shouldKeepWhenAttractionsArrayIsPopulatedAndMatches() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "Tommy Emmanuel Live",
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                            "_embedded": {"attractions": [{"name": "Tommy Emmanuel"}]}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Tommy Emmanuel", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).hasSize(1);
    }

    @Test
    @DisplayName("#207: critical regression guard -- a populated but non-matching attractions array must not fall through to the title fallback")
    void shouldNotFallBackToTitleWhenAttractionsArrayIsPopulatedButNonMatching() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {
                            "name": "Tommy Emmanuel Live",
                            "dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}},
                            "_embedded": {"attractions": [{"name": "Some Other Act"}]}
                          }
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Tommy Emmanuel", "78701", null, null, 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }
}
