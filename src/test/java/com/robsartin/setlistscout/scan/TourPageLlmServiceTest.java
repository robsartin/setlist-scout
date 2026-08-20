package com.robsartin.setlistscout.scan;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.robsartin.setlistscout.service.LogCapture;
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

class TourPageLlmServiceTest {

    private MockWebServer server;
    private TourPageLlmService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new TourPageLlmService(TestAppProperties.withKeys(), server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("logs a DEBUG line with cause for a malformed response line, and still parses the well-formed ones")
    void logsDebugOnMalformedLine() {
        server.enqueue(json("""
                {"content": [{"text": "2026-07-04 | The Fillmore | San Francisco\\nnotadate | Some Venue | Some City\\n2026-08-01 | The Fox Theatre | Atlanta"}]}
                """));

        try (LogCapture logs = LogCapture.attachAt(TourPageLlmService.class, Level.DEBUG)) {
            List<TourPageLlmService.ExtractedShow> result = service.extractShows("Dawes", "irrelevant page text");

            assertThat(result).hasSize(2); // the malformed line is skipped, the well-formed ones survive

            ILoggingEvent debugEvent = logs.events().stream()
                    .filter(e -> e.getLevel() == Level.DEBUG)
                    .filter(e -> e.getThrowableProxy() != null)
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "expected a DEBUG log with a cause for the malformed line"));
            assertThat(debugEvent.getThrowableProxy().getClassName()).isNotBlank();
        }
    }

    @Test
    @DisplayName("parses the performer and kind fields (case-insensitively) when the model returns five "
            + "fields (#208)")
    void parsesPerformerAndKindFromFiveFieldLine() {
        server.enqueue(json("""
                {"content": [{"text": "2026-07-04 | Cap City Comedy Club | Austin | Some Comedian | Comedy"}]}
                """));

        List<TourPageLlmService.ExtractedShow> result =
                service.extractShows("Cap City Comedy Club", "irrelevant page text");

        assertThat(result).hasSize(1);
        TourPageLlmService.ExtractedShow show = result.get(0);
        assertThat(show.performer()).isEqualTo("Some Comedian");
        assertThat(show.kind()).isEqualTo(Show.Kind.COMEDY);
    }

    @Test
    @DisplayName("defaults performer to null and kind to MUSIC when the model returns only three fields "
            + "(backward compatibility) (#208)")
    void defaultsPerformerAndKindOnThreeFieldLine() {
        server.enqueue(json("""
                {"content": [{"text": "2026-07-04 | The Fillmore | San Francisco"}]}
                """));

        List<TourPageLlmService.ExtractedShow> result = service.extractShows("Dawes", "irrelevant page text");

        assertThat(result).hasSize(1);
        TourPageLlmService.ExtractedShow show = result.get(0);
        assertThat(show.performer()).isNull();
        assertThat(show.kind()).isEqualTo(Show.Kind.MUSIC);
    }
}
