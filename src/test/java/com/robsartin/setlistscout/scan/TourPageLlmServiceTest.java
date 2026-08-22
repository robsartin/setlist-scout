package com.robsartin.setlistscout.scan;

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
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TourPageLlmServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

    /** Reads the one request the service sent and returns the prompt text of its "content" field. */
    private String capturedPromptText() throws InterruptedException, IOException {
        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        return body.path("messages").get(0).path("content").asText();
    }

    /**
     * A non-repeating string ("0123456789101112...") truncated to {@code length} chars. Unlike a
     * repeated filler character, no two differently-positioned or differently-sized windows of
     * this string are equal, so an {@code endsWith} assertion against a slice of it proves both
     * the exact cut point *and* the exact length of whatever the production code actually sent --
     * a same-length homogeneous filler could pass that assertion by coincidence even from the
     * wrong offset.
     */
    private static String longDigitString(int length) {
        StringBuilder sb = new StringBuilder(length + 16);
        int i = 0;
        while (sb.length() < length) {
            sb.append(i++);
        }
        return sb.substring(0, length);
    }

    @Test
    @DisplayName("logs a DEBUG line with cause for a malformed response line, and still parses the well-formed ones")
    void logsDebugOnMalformedLine() {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": "2026-07-04 | The Fillmore | San Francisco\\nnotadate | Some Venue | Some City\\n2026-08-01 | The Fox Theatre | Atlanta"}]}
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
                {"content": [{"type": "text", "text": "2026-07-04 | Cap City Comedy Club | Austin | Some Comedian | Comedy"}]}
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
                {"content": [{"type": "text", "text": "2026-07-04 | The Fillmore | San Francisco"}]}
                """));

        List<TourPageLlmService.ExtractedShow> result = service.extractShows("Dawes", "irrelevant page text");

        assertThat(result).hasSize(1);
        TourPageLlmService.ExtractedShow show = result.get(0);
        assertThat(show.performer()).isNull();
        assertThat(show.kind()).isEqualTo(Show.Kind.MUSIC);
    }

    // ---- #208: raise the page-text cap so venue calendars survive it ----------------------

    @Test
    @DisplayName("truncates page text to the new 200,000-char cap, not the old 8,000-char cap, when the "
            + "page exceeds it (#208)")
    void truncatesOverCapTextToTheNewCapNotTheOldOne() throws InterruptedException, IOException {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));
        // Comfortably past both the old 8,000-char cap and the new 200,000-char cap. pageText is
        // non-repeating (see longDigitString), so the prompt's tail can equal pageText[0,200_000)
        // only if that's actually what got sent -- not pageText[0,8_000) (the old cap) and not
        // the full 250,000 chars (cap silently dropped).
        String pageText = longDigitString(250_000);

        service.extractShows("Cap City Comedy Club", pageText);

        String prompt = capturedPromptText();
        assertThat(prompt).endsWith(pageText.substring(0, 200_000));
    }

    @Test
    @DisplayName("sends page text unmodified when it's under the new 200,000-char cap, even though it's "
            + "well over the old 8,000-char cap (#208)")
    void sendsUnderCapTextUnmodified() throws InterruptedException, IOException {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));
        // Mirrors the real Cap City Comedy Club calendar (149,420 chars, #208) that the old
        // 8,000-char cap silently discarded 94.6% of.
        String pageText = longDigitString(150_000);

        service.extractShows("Cap City Comedy Club", pageText);

        String prompt = capturedPromptText();
        assertThat(prompt).endsWith(pageText);
    }

    @Test
    @DisplayName("sends a max_tokens budget sized to hold the full 181-show Cap City calendar "
            + "(measured 6,906 output tokens), not the old 1000 (#208)")
    void sendsRaisedMaxTokensBudget() throws InterruptedException, IOException {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));

        service.extractShows("Cap City Comedy Club", "short page text, far under any cap");

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        assertThat(body.path("max_tokens").asInt()).isEqualTo(12_000);
    }

    // ---- #211: read the text block even when extended thinking precedes it ----------------

    @Test
    @DisplayName("reads the text block even when a thinking block precedes it in content (#211)")
    void readsTextBlockWhenThinkingBlockPrecedesIt() {
        server.enqueue(json("""
                {"content": [{"type": "thinking", "thinking": "reasoning about the page..."}, \
                {"type": "text", "text": "2026-07-04 | The Fillmore | San Francisco\\n2026-08-01 | The Fox Theatre | Atlanta"}]}
                """));

        List<TourPageLlmService.ExtractedShow> result =
                service.extractShows("Dawes", "irrelevant page text");

        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("logs a WARN with stop_reason when the response contains no text block at all (#211)")
    void logsWarnWhenNoTextBlockPresent() {
        server.enqueue(json("""
                {"content": [{"type": "thinking", "thinking": "reasoning about the page..."}], \
                "stop_reason": "max_tokens"}
                """));

        try (LogCapture logs = LogCapture.attach(TourPageLlmService.class)) {
            List<TourPageLlmService.ExtractedShow> result =
                    service.extractShows("Cap City Comedy Club", "irrelevant page text");

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
    @DisplayName("does not log a warning when a text block is present but legitimately parses to zero shows (#211)")
    void doesNotWarnWhenTextBlockParsesToZeroShows() {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));

        try (LogCapture logs = LogCapture.attach(TourPageLlmService.class)) {
            List<TourPageLlmService.ExtractedShow> result =
                    service.extractShows("Some Venue With No Shows", "irrelevant page text");

            assertThat(result).isEmpty();
            assertThat(logs.events()).noneMatch(e -> e.getLevel() == Level.WARN);
        }
    }

    @Test
    @DisplayName("disables extended thinking so the full output budget goes to extraction, not reasoning (#211)")
    void disablesExtendedThinking() throws InterruptedException, IOException {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));

        service.extractShows("Dawes", "irrelevant page text");

        RecordedRequest request = server.takeRequest();
        JsonNode body = MAPPER.readTree(request.getBody().readUtf8());
        assertThat(body.path("thinking").path("type").asText()).isEqualTo("disabled");
    }

    // ---- #218: ASO season-announcement page -- year resolution + blank-venue passthrough --------
    //
    // The ASO page (austinsymphony.org/season-announcement/) states a season ("2026-27 Season") and
    // then lists bare month/day headers with no year and no venue anywhere on the page. The actual
    // year-resolution reasoning happens inside the model, which these stubbed-response tests cannot
    // exercise -- what they CAN and must prove is that the prompt we send actually carries the
    // instructions that make correct resolution possible, and that a compliant "I can't tell, and a
    // blank venue" response is not silently discarded by our own parsing before it ever reaches the
    // artist-default substitution downstream (BandSiteShowSource, #218).

    @Test
    @DisplayName("prompt instructs resolving a month-only date against a season stated on the page, "
            + "and to omit rather than guess a date it can't confidently resolve -- a wrong year is "
            + "worse than a missing show (#218)")
    void promptInstructsSeasonAnchoredYearResolution() throws InterruptedException, IOException {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));

        service.extractShows("Austin Symphony Orchestra", "2026-27 Season\nOCTOBER Friday 23");

        String prompt = capturedPromptText();
        assertThat(prompt).contains("resolve each date's year against the stated season");
        assertThat(prompt).contains("omit that show entirely rather than guessing its year");
        assertThat(prompt).contains("a wrong date is worse than a missing show");
    }

    @Test
    @DisplayName("prompt instructs that one heading naming more than one date is more than one show, "
            + "e.g. \"Friday 11 & Sunday 13\" under a single month header (#218)")
    void promptInstructsMultipleDatesUnderOneHeadingAreSeparateShows() throws InterruptedException, IOException {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));

        service.extractShows("Austin Symphony Orchestra", "Friday 11 & Sunday 13");

        String prompt = capturedPromptText();
        assertThat(prompt).contains("describes more than one show");
        assertThat(prompt).contains("emit one line per date, never one line for the pair");
    }

    @Test
    @DisplayName("prompt instructs leaving the venue field blank rather than guessing one when the "
            + "page doesn't state a venue (#218)")
    void promptInstructsBlankVenueOverGuessing() throws InterruptedException, IOException {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": ""}]}
                """));

        service.extractShows("Austin Symphony Orchestra", "irrelevant page text");

        String prompt = capturedPromptText();
        assertThat(prompt).contains("leave the Venue name field blank rather than guessing one");
    }

    @Test
    @DisplayName("a line with a blank venue field is not dropped -- it survives as a show with a "
            + "blank venue, so an artist's default venue can be applied downstream (#218). Before "
            + "this change a `!venue.isBlank()` guard discarded the whole line, which is exactly how "
            + "a page like ASO's (names no hall anywhere) would extract nothing while the scan still "
            + "logged success -- the #211 failure shape")
    void blankVenueLineIsNotDropped() {
        server.enqueue(json("""
                {"content": [{"type": "text", "text": "2026-09-11 |  |  | Austin Symphony Orchestra | MUSIC"}]}
                """));

        List<TourPageLlmService.ExtractedShow> result =
                service.extractShows("Austin Symphony Orchestra", "irrelevant page text");

        assertThat(result).hasSize(1);
        TourPageLlmService.ExtractedShow show = result.get(0);
        assertThat(show.date()).isEqualTo(LocalDate.of(2026, 9, 11));
        assertThat(show.venue()).isBlank();
        assertThat(show.city()).isBlank();
    }
}
