package com.robsartin.setlistscout.expansion;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarArtistLlmServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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
                {"content": [{"type": "text", "text": "1. Bon Iver\\n- Fleet Foxes\\nThe National"}]}
                """));

        List<String> result = service.findSimilarArtists("Iron & Wine", 3);

        assertThat(result).containsExactly("Bon Iver", "Fleet Foxes", "The National");
    }

    @Test
    @DisplayName("should skip blank lines")
    void shouldSkipBlankLines() {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": "Bon Iver\\n\\nFleet Foxes"}]}
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

    // ---- #213: read the text block even when extended thinking precedes it ----------------

    @Test
    @DisplayName("should read the text block even when a thinking block precedes it in content (#213)")
    void shouldReadTextBlockWhenThinkingBlockPrecedesIt() {
        server.enqueue(json("""
                {"content": [{"type": "thinking", "thinking": "reasoning about the artist..."}, \
                {"type": "text", "text": "Bon Iver\\nFleet Foxes"}]}
                """));

        List<String> result = service.findSimilarArtists("Iron & Wine", 2);

        assertThat(result).containsExactly("Bon Iver", "Fleet Foxes");
    }

    @Test
    @DisplayName("should log a WARN with stop_reason when the response contains no text block at all (#213)")
    void shouldLogWarnWhenNoTextBlockPresent() {
        server.enqueue(json("""
                {"content": [{"type": "thinking", "thinking": "reasoning about the artist..."}], \
                "stop_reason": "max_tokens"}
                """));

        try (LogCapture logs = LogCapture.attach(SimilarArtistLlmService.class)) {
            List<String> result = service.findSimilarArtists("Iron & Wine", 3);

            assertThat(result).isEmpty();

            ILoggingEvent warnEvent = logs.events().stream()
                    .filter(e -> e.getLevel() == Level.WARN)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("expected a WARN log for the missing text block"));
            assertThat(warnEvent.getKeyValuePairs().stream()
                    .filter(kv -> "stop_reason".equals(kv.key))
                    .map(kv -> String.valueOf(kv.value))
                    .findFirst())
                    .contains("max_tokens");
        }
    }

    @Test
    @DisplayName("should not warn when a text block is present but legitimately names no artists (#213)")
    void shouldNotWarnWhenTextBlockNamesNoArtists() {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));

        try (LogCapture logs = LogCapture.attach(SimilarArtistLlmService.class)) {
            List<String> result = service.findSimilarArtists("Some Obscure Band", 3);

            assertThat(result).isEmpty();
            assertThat(logs.events()).noneMatch(e -> e.getLevel() == Level.WARN);
        }
    }

    @Test
    @DisplayName("should disable extended thinking so the output budget goes to the list, not reasoning (#213)")
    void shouldDisableExtendedThinking() throws InterruptedException, IOException {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));

        service.findSimilarArtists("Iron & Wine", 3);

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
    }
}
