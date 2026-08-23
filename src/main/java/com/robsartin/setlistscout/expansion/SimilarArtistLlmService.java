package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.shared.AnthropicMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Second opinion on similar artists, useful for niche/regional acts that thin
 * structured databases (Last.fm, MusicBrainz) don't cover well. Cross-checked
 * against LastFmService during expansion -- names found by both sources are
 * flagged higher-confidence during review.
 */
@Service
public class SimilarArtistLlmService {

    private static final Logger log = LoggerFactory.getLogger(SimilarArtistLlmService.class);

    /** The tool the model must call, so commentary has nowhere to live (#253). */
    private static final String TOOL = "record_similar_artists";

    private final RestClient restClient;
    private final String apiKey;

    /**
     * Base URL is injectable (#184) rather than hardcoded, so tests can point it at an unroutable
     * address instead of making live calls to Anthropic. Also the test seam a plain unit test uses
     * to point at a local stub server directly, bypassing Spring entirely.
     */
    @Autowired
    public SimilarArtistLlmService(AppProperties props,
            @Value("${setlistscout.similar-artist-llm.base-url:https://api.anthropic.com/v1}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.apiKey = props.apis().anthropicApiKey();
    }

    @SuppressWarnings("unchecked")
    public List<String> findSimilarArtists(String artistName, int count) {
        List<String> result = new ArrayList<>();
        String prompt = "List exactly " + count + " bands or musicians similar in sound/genre to \""
                + artistName + "\". One name per line, no numbering, no commentary.";

        // Model string as of mid-2026 -- check docs.claude.com/en/docs/about-claude/models
        // if this starts returning errors, since these names change over time.
        Map<String, Object> body = Map.of(
                "model", "claude-sonnet-5",
                "max_tokens", 300,
                "thinking", AnthropicMessages.THINKING_DISABLED,
                "tools", List.of(AnthropicMessages.nameListTool(TOOL, "names", "Record the similar bands or musicians you identified. Names only.")),
                "tool_choice", AnthropicMessages.forceTool(TOOL),
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
                    .addKeyValue("source", "similar-llm")
                    .addKeyValue("artist", artistName)
                    .log("similar-artists request failed");
            response = Map.of();
        }

        Optional<List<String>> names = AnthropicMessages.nameList(response, TOOL, "names");
        if (names.isEmpty()) {
            // Total failure (the call errored, or the model answered in prose instead of calling
            // the tool) and "the model knows of none" must not look identical from the outside
            // (#213) -- the latter calls the tool with an empty array.
            var warn = log.atWarn()
                    .addKeyValue("source", "similar-llm")
                    .addKeyValue("artist", artistName);
            Object stopReason = AnthropicMessages.stopReason(response).orElse(null);
            if (stopReason != null) {
                warn = warn.addKeyValue("stop_reason", stopReason);
            }
            warn.log("similar-artists produced no tool call");
            return result;
        }

        result.addAll(names.get());
        log.atDebug().addKeyValue("source", "similar-llm").addKeyValue("artist", artistName)
                .addKeyValue("count", result.size()).log("similar artists request");
        return result;
    }
}
