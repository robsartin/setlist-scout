package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.service.TestAppProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LastFmServiceTest {

    private MockWebServer server;
    private LastFmService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new LastFmService(TestAppProperties.withKeys(), server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("should return similar artist names")
    void shouldReturnSimilarArtists() {
        server.enqueue(json("""
                {"similarartists": {"artist": [{"name": "Nickel Creek"}, {"name": "Punch Brothers"}]}}
                """));

        List<String> similar = service.findSimilarArtists("Dawes", 8);

        assertThat(similar).containsExactly("Nickel Creek", "Punch Brothers");
    }

    @Test
    @DisplayName("should return an empty list when similarartists is missing")
    void shouldReturnEmptyWhenSimilarArtistsMissing() {
        server.enqueue(json("{}"));

        List<String> similar = service.findSimilarArtists("Dawes", 8);

        assertThat(similar).isEmpty();
    }

    @Test
    @DisplayName("should return an empty list when the artist array is missing")
    void shouldReturnEmptyWhenArtistArrayMissing() {
        server.enqueue(json("""
                {"similarartists": {}}
                """));

        List<String> similar = service.findSimilarArtists("Dawes", 8);

        assertThat(similar).isEmpty();
    }
}
