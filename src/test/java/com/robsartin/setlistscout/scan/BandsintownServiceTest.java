package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.service.TestAppProperties;
import com.robsartin.setlistscout.shared.SourceCallFailedException;
import com.robsartin.setlistscout.shared.TransientSourceException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BandsintownServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 12, 31, 0, 0);
    // Search origin: downtown Austin, 50-mile radius.
    private static final double LAT = 30.2672;
    private static final double LON = -97.7431;
    private static final int RADIUS = 50;

    private MockWebServer server;
    private BandsintownService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new BandsintownService(TestAppProperties.withKeys(), server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    @Test
    @DisplayName("keeps shows within the radius and drops shows outside it")
    void keepsInRadiusDropsOutOfRadius() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "venue": {"name": "Moody Center", "latitude": "30.2814", "longitude": "-97.7320"}},
                  {"datetime": "2026-06-02T20:00:00", "venue": {"name": "The Fillmore", "latitude": "37.7840", "longitude": "-122.4330"}}
                ]
                """));

        List<Show> shows = service.searchShows("Dawes", LAT, LON, RADIUS, START, END);

        assertThat(shows).extracting(Show::getVenueName).containsExactly("Moody Center");
    }

    @Test
    @DisplayName("drops a show whose venue has no coordinates to check")
    void dropsVenueWithoutCoordinates() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "venue": {"name": "Coordinate-less Club"}}
                ]
                """));

        List<Show> shows = service.searchShows("Dawes", LAT, LON, RADIUS, START, END);

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("keeps all in-window shows when the search location has no coordinates (degraded)")
    void keepsAllWhenNoOrigin() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "venue": {"name": "Moody Center", "latitude": "30.28", "longitude": "-97.73"}},
                  {"datetime": "2026-06-02T20:00:00", "venue": {"name": "The Fillmore", "latitude": "37.78", "longitude": "-122.43"}}
                ]
                """));

        List<Show> shows = service.searchShows("Dawes", null, null, RADIUS, START, END);

        assertThat(shows).extracting(Show::getVenueName).containsExactly("Moody Center", "The Fillmore");
    }

    @Test
    @DisplayName("drops shows outside the requested date window")
    void dropsShowsOutsideWindow() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2025-01-01T20:00:00", "venue": {"name": "Too Early", "latitude": "30.27", "longitude": "-97.74"}},
                  {"datetime": "2026-06-01T20:00:00", "venue": {"name": "In Window", "latitude": "30.27", "longitude": "-97.74"}},
                  {"datetime": "2027-01-01T20:00:00", "venue": {"name": "Too Late", "latitude": "30.27", "longitude": "-97.74"}}
                ]
                """));

        List<Show> shows = service.searchShows("Dawes", LAT, LON, RADIUS, START, END);

        assertThat(shows).extracting(Show::getVenueName).containsExactly("In Window");
    }

    /**
     * Until #265 this asserted a 500 returned an EMPTY LIST. That was the behaviour, and it is
     * precisely the defect: an empty list is what a successful search of an artist with no upcoming
     * shows returns, so ScanPoller could not tell the two apart and recorded the failure as a
     * success. A 500 is now transient (#263's ladder, which Bandsintown never had), and the
     * assertion is inverted to say so.
     */
    @Test
    @DisplayName("issue #265: a server error is no longer reported as an empty list")
    void aServerErrorIsNotAnEmptyList() {
        server.enqueue(new MockResponse().setResponseCode(500));

        assertThatThrownBy(() -> service.searchShows("Dawes", LAT, LON, RADIUS, START, END))
                .isInstanceOf(TransientSourceException.class);
    }

    @Test
    @DisplayName("should label the show with the lineup headliner, not the search keyword")
    void labelsShowWithLineupHeadlinerNotSearchKeyword() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "lineup": ["The Damn Torpedoes"],
                   "venue": {"name": "Moody Center", "latitude": "30.2814", "longitude": "-97.7320"}}
                ]
                """));

        List<Show> shows = service.searchShows("Tom Petty", LAT, LON, RADIUS, START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("The Damn Torpedoes");
    }

    @Test
    @DisplayName("falls back to the search keyword when lineup is missing")
    void fallsBackToKeywordWhenLineupMissing() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00",
                   "venue": {"name": "Moody Center", "latitude": "30.2814", "longitude": "-97.7320"}}
                ]
                """));

        List<Show> shows = service.searchShows("Tom Petty", LAT, LON, RADIUS, START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Tom Petty");
    }

    @Test
    @DisplayName("falls back to the search keyword when lineup is not a list (malformed API shape)")
    void fallsBackWhenLineupIsNotAList() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "lineup": "The Damn Torpedoes",
                   "venue": {"name": "Moody Center", "latitude": "30.2814", "longitude": "-97.7320"}}
                ]
                """));

        List<Show> shows = service.searchShows("Tom Petty", LAT, LON, RADIUS, START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Tom Petty");
    }

    @Test
    @DisplayName("falls back to the search keyword when lineup is an empty list")
    void fallsBackWhenLineupIsEmpty() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "lineup": [],
                   "venue": {"name": "Moody Center", "latitude": "30.2814", "longitude": "-97.7320"}}
                ]
                """));

        List<Show> shows = service.searchShows("Tom Petty", LAT, LON, RADIUS, START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Tom Petty");
    }

    @Test
    @DisplayName("falls back to the search keyword when the first lineup entry is blank")
    void fallsBackWhenLineupFirstEntryIsBlank() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "lineup": [""],
                   "venue": {"name": "Moody Center", "latitude": "30.2814", "longitude": "-97.7320"}}
                ]
                """));

        List<Show> shows = service.searchShows("Tom Petty", LAT, LON, RADIUS, START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getArtistName()).isEqualTo("Tom Petty");
    }

    @Test
    @DisplayName("URL-encodes spaces in the artist name in the request path")
    void encodesArtistNameInPath() throws InterruptedException {
        server.enqueue(jsonEvents("[]"));

        service.searchShows("Tom Petty", LAT, LON, RADIUS, START, END);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).startsWith("/artists/Tom%20Petty/events");
    }

    private static MockResponse jsonEvents(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("issue #265: a 403 THROWS rather than returning an empty list -- swallowing it is "
            + "how 2,857 consecutive 403s were recorded as 2,857 successful scans")
    void a403ThrowsSourceCallFailed() {
        server.enqueue(new MockResponse().setResponseCode(403)
                .setBody("{\"Message\":\"User is not authorized to access this resource\"}"));

        assertThatThrownBy(() -> service.searchShows("Dawes", LAT, LON, RADIUS, START, END))
                .isInstanceOf(SourceCallFailedException.class)
                .extracting(e -> ((SourceCallFailedException) e).signature())
                .isEqualTo("403");
    }

    @Test
    @DisplayName("issue #265: a 429 stays a TransientSourceException -- #263's retry ladder must "
            + "keep working, and Bandsintown never had it because it swallowed everything")
    void a429IsTransient() {
        server.enqueue(new MockResponse().setResponseCode(429));

        assertThatThrownBy(() -> service.searchShows("Dawes", LAT, LON, RADIUS, START, END))
                .isInstanceOf(TransientSourceException.class);
    }

    @Test
    @DisplayName("issue #265: a 5xx stays a TransientSourceException")
    void a5xxIsTransient() {
        server.enqueue(new MockResponse().setResponseCode(503));

        assertThatThrownBy(() -> service.searchShows("Dawes", LAT, LON, RADIUS, START, END))
                .isInstanceOf(TransientSourceException.class);
    }

    @Test
    @DisplayName("issue #265: an empty result is still an empty result -- a source that legitimately "
            + "finds nothing must NOT look like a failure")
    void anEmptyResultIsNotAFailure() {
        server.enqueue(jsonEvents("[]"));

        assertThat(service.searchShows("Dawes", LAT, LON, RADIUS, START, END)).isEmpty();
    }
}
