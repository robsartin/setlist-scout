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
                                "_embedded": {"venues": [{"name": "Moody Center", "city": {"name": "Austin"}}]},
                                "priceRanges": [{"min": 45.5}],
                                "url": "https://example.com/tickets"
                              }
                            ]
                          }
                        }
                        """));

        List<Show> shows = service.searchShows("Dawes", "78701", 50,
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
    @DisplayName("should return an empty list when _embedded is missing")
    void shouldReturnEmptyWhenNoEmbedded() {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));

        List<Show> shows = service.searchShows("Dawes", "78701", 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("should default venue name and leave price null when absent")
    void shouldDefaultMissingFields() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""
                        {"_embedded": {"events": [
                          {"dates": {"start": {"dateTime": "2026-09-01T19:00:00Z"}}}
                        ]}}
                        """));

        List<Show> shows = service.searchShows("Dawes", "78701", 50,
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

        List<Show> shows = service.searchShows("Dawes", "78701", 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        assertThat(shows).isEmpty();
    }

    @Test
    @DisplayName("should send the ZIP as postalCode plus the radius")
    void shouldSendPostalCodeAndRadius() throws InterruptedException {
        server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{}"));

        service.searchShows("Dawes", "78701", 50,
                LocalDateTime.now(), LocalDateTime.now().plusMonths(1));

        RecordedRequest request = server.takeRequest();
        assertThat(request.getPath()).contains("postalCode=78701");
        assertThat(request.getPath()).contains("radius=50");
    }
}
