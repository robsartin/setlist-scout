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
    @DisplayName("should read names from the forced tool_use block (#253)")
    void shouldReadNamesFromToolUse() {
        server.enqueue(json("""
                {"content": [{"type": "tool_use", "name": "record_similar_artists", \
                "input": {"names": ["Bon Iver", "Fleet Foxes", "The National"]}}]}
                """));

        List<String> result = service.findSimilarArtists("Iron & Wine", 3);

        assertThat(result).containsExactly("Bon Iver", "Fleet Foxes", "The National");
    }

    @Test
    @DisplayName("should ignore a text block that precedes the tool_use block (#253)")
    void shouldIgnorePrecedingCommentary() {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": "Here are 8 similar artists:"}, \
                {"type": "tool_use", "name": "record_similar_artists", \
                "input": {"names": ["Bon Iver", "Fleet Foxes"]}}]}
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
    @DisplayName("should read the tool_use block even when a thinking block precedes it (#213)")
    void shouldReadNamesWhenThinkingBlockPrecedesThem() {
        server.enqueue(json("""
                {"content": [{"type": "thinking", "thinking": "reasoning about the artist..."}, \
                {"type": "tool_use", "name": "record_similar_artists", \
                "input": {"names": ["Bon Iver", "Fleet Foxes"]}}]}
                """));

        List<String> result = service.findSimilarArtists("Iron & Wine", 2);

        assertThat(result).containsExactly("Bon Iver", "Fleet Foxes");
    }

    @Test
    @DisplayName("should log a WARN with stop_reason when the response contains no tool_use block at all (#213)")
    void shouldLogWarnWhenNoToolUseBlockPresent() {
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
                    .orElseThrow(() -> new AssertionError("expected a WARN log for the missing tool_use block"));
            assertThat(warnEvent.getKeyValuePairs().stream()
                    .filter(kv -> "stop_reason".equals(kv.key))
                    .map(kv -> String.valueOf(kv.value))
                    .findFirst())
                    .contains("max_tokens");
        }
    }

    @Test
    @DisplayName("should not warn when the tool_use block is present but legitimately names none (#213)")
    void shouldNotWarnWhenToolUseNamesNone() {
        server.enqueue(json("""
                {"content": [{"type": "tool_use", "name": "record_similar_artists", "input": {"names": []}}]}
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
                {"content": [{"type": "tool_use", "name": "record_similar_artists", "input": {"names": []}}]}
                """));

        service.findSimilarArtists("Iron & Wine", 3);

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    // ---- #253: commentary must never become an artist -------------------------------------

    @Test
    @DisplayName("should keep real names that look like sentences -- the anti-blocklist guard (#253)")
    void shouldKeepRealNamesThatLookLikeSentences() {
        server.enqueue(json("""
                {"content": [{"type": "tool_use", "name": "record_similar_artists", "input": {"names": [\
                "Does It Offend You, Yeah?", "I See Hawks in L.A.", "These United States", \
                "Grover Washington, Jr.", "Wau Y Los Arrrghs!!!"]}}]}
                """));

        List<String> result = service.findSimilarArtists("Iron & Wine", 5);

        assertThat(result).containsExactly("Does It Offend You, Yeah?", "I See Hawks in L.A.",
                "These United States", "Grover Washington, Jr.", "Wau Y Los Arrrghs!!!");
    }

    @Test
    @DisplayName("should return nothing when the model only produced prose, and WARN (#253)")
    void shouldReturnNothingForProseOnlyResponse() {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": \
                "I'd rather return nothing than provide fabricated names."}], \
                "stop_reason": "end_turn"}
                """));

        try (LogCapture logs = LogCapture.attach(SimilarArtistLlmService.class)) {
            List<String> result = service.findSimilarArtists("Iron & Wine", 3);

            assertThat(result).isEmpty();
            assertThat(logs.events()).anyMatch(e -> e.getLevel() == Level.WARN);
        }
    }

    @Test
    @DisplayName("should declare the tool and force the model to call it (#253)")
    void shouldDeclareAndForceTheTool() throws InterruptedException, IOException {
        server.enqueue(json("""
                {"content": [{"type": "tool_use", "name": "record_similar_artists", "input": {"names": []}}]}
                """));

        service.findSimilarArtists("Iron & Wine", 3);

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        assertThat(body.path("tools").get(0).path("name").asText()).isEqualTo("record_similar_artists");
        assertThat(body.path("tools").get(0).path("input_schema").path("properties")
                .path("names").path("type").asText()).isEqualTo("array");
        assertThat(body.path("tool_choice").path("type").asText()).isEqualTo("tool");
        assertThat(body.path("tool_choice").path("name").asText()).isEqualTo("record_similar_artists");
    }
}
