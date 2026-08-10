package com.robsartin.setlistscout.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TributeLlmServiceTest {

    private MockWebServer server;
    private TributeLlmService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new TributeLlmService(TestAppProperties.withKeys(), server.url("/").toString());
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
                {"content": [{"text": "1. The Iron Maidens\\n- Dread Zeppelin\\nMandonna"}]}
                """));

        List<String> result = service.findTributeBands("Iron Maiden", 3);

        assertThat(result).containsExactly("The Iron Maidens", "Dread Zeppelin", "Mandonna");
    }

    @Test
    @DisplayName("should skip blank lines")
    void shouldSkipBlankLines() {
        server.enqueue(json("""
                {"content": [{"text": "The Iron Maidens\\n\\nDread Zeppelin"}]}
                """));

        List<String> result = service.findTributeBands("Iron Maiden", 2);

        assertThat(result).containsExactly("The Iron Maidens", "Dread Zeppelin");
    }

    @Test
    @DisplayName("should return an empty list when the model reports no known tributes")
    void shouldReturnEmptyWhenNoneKnown() {
        server.enqueue(json("""
                {"content": [{"text": ""}]}
                """));

        List<String> result = service.findTributeBands("Some Obscure Band", 5);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return an empty list when content is missing")
    void shouldReturnEmptyWhenContentMissing() {
        server.enqueue(json("{}"));

        List<String> result = service.findTributeBands("Iron Maiden", 3);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("should return an empty list on server error")
    void shouldReturnEmptyOnServerError() {
        server.enqueue(new MockResponse().setResponseCode(500));

        List<String> result = service.findTributeBands("Iron Maiden", 3);

        assertThat(result).isEmpty();
    }
}
