package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.domain.Show;
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

class BandsintownServiceTest {

    private static final LocalDateTime START = LocalDateTime.of(2026, 1, 1, 0, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 12, 31, 0, 0);

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
    @DisplayName("should drop shows whose venue region does not match the state filter")
    void shouldDropOutOfStateShows() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "venue": {"name": "Moody Center", "city": "Austin", "region": "TX"}},
                  {"datetime": "2026-06-02T20:00:00", "venue": {"name": "Brooklyn Bowl", "city": "Brooklyn", "region": "NY"}}
                ]
                """));

        List<Show> shows = service.searchShows("Dawes", null, "TX", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getVenueName()).isEqualTo("Moody Center");
    }

    @Test
    @DisplayName("should keep a show when the venue has no region to check")
    void shouldKeepShowWithNoRegionData() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "venue": {"name": "Some Club", "city": "Austin"}}
                ]
                """));

        List<Show> shows = service.searchShows("Dawes", null, "TX", START, END);

        assertThat(shows).hasSize(1);
    }

    @Test
    @DisplayName("should cast a wide net when cityFilter is null but still apply the state filter")
    void shouldApplyStateFilterEvenWithNullCityFilter() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00", "venue": {"name": "Moody Center", "city": "Austin", "region": "TX"}},
                  {"datetime": "2026-06-02T20:00:00", "venue": {"name": "The Fillmore", "city": "San Francisco", "region": "CA"}}
                ]
                """));

        List<Show> shows = service.searchShows("Dawes", null, "TX", START, END);

        assertThat(shows).extracting(Show::getVenueName).containsExactly("Moody Center");
    }

    @Test
    @DisplayName("should drop shows outside the requested date window")
    void shouldDropShowsOutsideWindow() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2025-01-01T20:00:00", "venue": {"name": "Too Early", "city": "Austin", "region": "TX"}},
                  {"datetime": "2026-06-01T20:00:00", "venue": {"name": "In Window", "city": "Austin", "region": "TX"}},
                  {"datetime": "2027-01-01T20:00:00", "venue": {"name": "Too Late", "city": "Austin", "region": "TX"}}
                ]
                """));

        List<Show> shows = service.searchShows("Dawes", null, "TX", START, END);

        assertThat(shows).extracting(Show::getVenueName).containsExactly("In Window");
    }

    @Test
    @DisplayName("should default venue name when the venue is missing")
    void shouldDefaultMissingVenue() {
        server.enqueue(jsonEvents("""
                [
                  {"datetime": "2026-06-01T20:00:00"}
                ]
                """));

        List<Show> shows = service.searchShows("Dawes", null, "TX", START, END);

        assertThat(shows).hasSize(1);
        assertThat(shows.get(0).getVenueName()).isEqualTo("Unknown venue");
    }

    @Test
    @DisplayName("should return an empty list when the API errors")
    void shouldReturnEmptyOnServerError() {
        server.enqueue(new MockResponse().setResponseCode(500));

        List<Show> shows = service.searchShows("Dawes", null, "TX", START, END);

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("should URL-encode spaces in the artist name in the request path")
    void shouldEncodeArtistNameInPath() throws InterruptedException {
        server.enqueue(jsonEvents("[]"));

        service.searchShows("Tom Petty", null, "TX", START, END);

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).startsWith("/artists/Tom%20Petty/events");
    }

    private static MockResponse jsonEvents(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }
}
