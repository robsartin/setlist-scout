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

class SimilarArtistLlmServiceTest {

    private MockWebServer server;
    private SimilarArtistLlmService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new SimilarArtistLlmService(TestAppProperties.withKeys(), server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("should strip numbering and bullets but keep plain lines as-is")
    void shouldParseMixedFormattingLines() {
        server.enqueue(json("""
                {"content": [{"text": "1. Bon Iver\\n- Fleet Foxes\\nThe National"}]}
                """));

        List<String> result = service.findSimilarArtists("Iron & Wine", 3);

        assertThat(result).containsExactly("Bon Iver", "Fleet Foxes", "The National");
    }

    @Test
    @DisplayName("should skip blank lines")
    void shouldSkipBlankLines() {
        server.enqueue(json("""
                {"content": [{"text": "Bon Iver\\n\\nFleet Foxes"}]}
                """));

        List<String> result = service.findSimilarArtists("Iron & Wine", 2);

        assertThat(result).containsExactly("Bon Iver", "Fleet Foxes");
    }

    @Test
    @DisplayName("should return an empty list when content is missing")
    void shouldReturnEmptyWhenContentMissing() {
        server.enqueue(json("{}"));

        List<String> result = service.findSimilarArtists("Iron & Wine", 3);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return an empty list on server error")
    void shouldReturnEmptyOnServerError() {
        server.enqueue(new MockResponse().setResponseCode(500));

        List<String> result = service.findSimilarArtists("Iron & Wine", 3);

        assertThat(result).isEmpty();
    }
}
