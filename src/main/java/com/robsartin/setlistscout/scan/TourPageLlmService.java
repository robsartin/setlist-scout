package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM fallback for the band-site scraper (#22): when a tour page has no structured JSON-LD
 * events, ask Claude to extract shows from the page text. Returns each show as an
 * {@link ExtractedShow} -- date, venue, city, plus an optional performer and a kind (#208), since
 * the source page may be a venue calendar listing other acts rather than the tracked artist's own
 * tour page. Degrades to empty on any error so a scrape never breaks a scan.
 */
@Service
public class TourPageLlmService {

    private static final Logger log = LoggerFactory.getLogger(TourPageLlmService.class);

    /**
     * Safety bound against a pathological page, not a cost lever (#208) -- the measured median
     * {@code official_site_url} page is 1,646 chars (18-page production sample), and 80% of
     * sampled pages never reach even the old 8,000-char cap. Cap City Comedy Club's real venue
     * calendar (the motivating case, #208) is 149,420 chars and fits comfortably under this.
     */
    private static final int PAGE_TEXT_CHAR_CAP = 200_000;

    /**
     * Sized for a full venue-calendar response, not a typical one (#208). ~50 Cap City Comedy
     * Club shows in the "YYYY-MM-DD | Venue | City | Performer | KIND" format measured at
     * roughly 1,100-1,400 tokens across two realistic name-length samples (GPT-class tokenizer
     * proxy -- Claude's own tokenizer isn't available offline) -- already past the old 1000-token
     * budget on its own. This leaves comfortable headroom above that measured range for longer
     * venue/performer names, tokenizer differences, and incidental model preamble.
     */
    private static final int MAX_OUTPUT_TOKENS = 4000;

    private final RestClient restClient;
    private final String apiKey;

    /**
     * Base URL is injectable (#184) rather than hardcoded, so tests can point it at an unroutable
     * address instead of making live calls to Anthropic. Also the test seam a plain unit test uses
     * to point at a local stub server directly, bypassing Spring entirely.
     */
    @Autowired
    public TourPageLlmService(AppProperties props,
            @Value("${setlistscout.tour-page-llm.base-url:https://api.anthropic.com/v1}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.apiKey = props.apis().anthropicApiKey();
    }

    @SuppressWarnings("unchecked")
    public List<ExtractedShow> extractShows(String artistName, String pageText) {
        List<ExtractedShow> result = new ArrayList<>();
        // Cap the text sent to the model -- tour pages can be huge; the dates are near the top.
        String text = pageText.length() > PAGE_TEXT_CHAR_CAP ? pageText.substring(0, PAGE_TEXT_CHAR_CAP) : pageText;
        String prompt = "This is the text of a tour/shows page belonging to \"" + artistName + "\","
                + " which may be a band's own site or a venue's calendar of other acts."
                + " Extract each upcoming live show as one line in exactly this format:\n"
                + "YYYY-MM-DD | Venue name | City | Performer | MUSIC or COMEDY\n"
                + "Performer is the name of the act actually performing that date -- for a band's own"
                + " tour page that is \"" + artistName + "\" itself; for a venue calendar it is whoever"
                + " is booked that date, not the venue. Classify each show as MUSIC or COMEDY.\n"
                + "One show per line, no header, no commentary. If there are no shows, return nothing.\n\n"
                + text;

        Map<String, Object> body = Map.of(
                "model", "claude-sonnet-5",
                "max_tokens", MAX_OUTPUT_TOKENS,
                // Mechanical extraction from supplied text, not a reasoning task -- extended
                // thinking is on by default and can consume the whole output budget before a
                // text block is ever produced (#211).
                "thinking", Map.of("type", "disabled"),
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "tour-llm")
                    .addKeyValue("artist", artistName)
                    .log("tour-page extraction request failed");
            response = Map.of();
        }

        if (response == null) return result;
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null || content.isEmpty()) return result;

        // content is a list of typed blocks, not always text-first -- claude-sonnet-5 returns
        // extended thinking by default, so content[0] can be a "thinking" block with no "text"
        // key (#211). Scan for the first block actually typed "text" instead of indexing 0.
        String responseText = null;
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type"))) {
                responseText = (String) block.get("text");
                break;
            }
        }
        if (responseText == null) {
            // Total failure (e.g. thinking consumed the whole output budget) and "this page
            // genuinely has no shows" must not look identical from the outside (#211) -- the
            // latter always produces a text block, even an empty/non-matching one.
            var warn = log.atWarn()
                    .addKeyValue("source", "tour-llm")
                    .addKeyValue("artist", artistName);
            Object stopReason = response.get("stop_reason");
            if (stopReason != null) {
                warn = warn.addKeyValue("stop_reason", stopReason);
            }
            warn.log("tour-page extraction returned no text block");
            return result;
        }

        for (String line : responseText.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            try {
                LocalDate date = LocalDate.parse(parts[0].trim());
                String venue = parts[1].trim();
                String city = parts[2].trim();
                // Both trail the original 3-field format (#22); a model that doesn't comply still
                // yields a usable show rather than zero shows -- see class javadoc.
                String performerRaw = parts.length > 3 ? parts[3].trim() : "";
                String performer = performerRaw.isBlank() ? null : performerRaw;
                String kindRaw = parts.length > 4 ? parts[4].trim() : "";
                Show.Kind kind = kindRaw.equalsIgnoreCase("COMEDY") ? Show.Kind.COMEDY : Show.Kind.MUSIC;
                if (!venue.isBlank()) {
                    result.add(new ExtractedShow(date, venue, city, performer, kind));
                }
            } catch (Exception e) {
                // skip lines that aren't a well-formed "date | venue | city"; one bad line among
                // possibly many good ones, not a whole-request failure, so DEBUG rather than WARN.
                log.atDebug().setCause(e)
                        .addKeyValue("source", "tour-llm")
                        .addKeyValue("artist", artistName)
                        .addKeyValue("line", line)
                        .log("malformed tour-page line, skipping");
            }
        }
        log.atDebug().addKeyValue("source", "tour-llm").addKeyValue("artist", artistName)
                .addKeyValue("count", result.size()).log("tour-page extraction");
        return result;
    }

    /**
     * @param performer the performing act's name, or {@code null} when the model didn't return a
     *     4th field (either an older-style 3-field response, or a genuinely blank field -- both
     *     collapse to {@code null} so callers have one fallback check, not two).
     * @param kind {@link Show.Kind#MUSIC} unless the model's 5th field trims to "COMEDY"
     *     (case-insensitive); also the default when the field is absent.
     */
    public record ExtractedShow(LocalDate date, String venue, String city, String performer, Show.Kind kind) {}
}
