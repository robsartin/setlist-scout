package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Discovers cover/tribute acts for a band via the LLM. Tribute bands don't appear
 * in MusicBrainz lineup relations or Last.fm "similar artist" lists, so this is the
 * only source for them (see ADR-0017). Results feed the pending-review gate like any
 * other expansion source; false positives are absorbed there (ADR-0004).
 */
@Service
public class TributeLlmService {

    private static final Logger log = LoggerFactory.getLogger(TributeLlmService.class);

    private final RestClient restClient;
    private final String apiKey;
    private static final Pattern LINE_ITEM = Pattern.compile("^\\s*[-\\d.]+\\s*[).]?\\s*(.+)$");

    /**
     * Base URL is injectable (#184) rather than hardcoded, so tests can point it at an unroutable
     * address instead of making live calls to Anthropic. Also the test seam a plain unit test uses
     * to point at a local stub server directly, bypassing Spring entirely.
     */
    @Autowired
    public TributeLlmService(AppProperties props,
            @Value("${setlistscout.tribute-llm.base-url:https://api.anthropic.com/v1}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.apiKey = props.apis().anthropicApiKey();
    }

    @SuppressWarnings("unchecked")
    public List<String> findTributeBands(String artistName, int count) {
        List<String> result = new ArrayList<>();
        String prompt = "List up to " + count + " well-known tribute or cover bands that perform"
                + " the music of \"" + artistName + "\". Include only real, currently- or recently-"
                + "active tribute acts. One name per line, no numbering, no commentary."
                + " If you don't know any, return nothing.";

        // Model string as of mid-2026 -- check docs.claude.com/en/docs/about-claude/models
        // if this starts returning errors, since these names change over time.
        Map<String, Object> body = Map.of(
                "model", "claude-sonnet-5",
                "max_tokens", 300,
                // A recall-and-list request, not a reasoning task -- extended thinking is on by
                // default and can consume the whole output budget before a text block is ever
                // produced (#213, same fix as #211).
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
                    .addKeyValue("source", "tribute-llm")
                    .addKeyValue("artist", artistName)
                    .log("tribute-bands request failed");
            response = Map.of();
        }

        if (response == null) return result;
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null || content.isEmpty()) return result;

        // content is a list of typed blocks, not always text-first -- claude-sonnet-5 returns
        // extended thinking by default, so content[0] can be a "thinking" block with no "text"
        // key (#213). Scan for the first block actually typed "text" instead of indexing 0.
        String text = null;
        for (Map<String, Object> block : content) {
            if ("text".equals(block.get("type"))) {
                text = (String) block.get("text");
                break;
            }
        }
        if (text == null) {
            // Total failure (e.g. thinking consumed the whole output budget) and "the model knows
            // of none" must not look identical from the outside (#213) -- the latter always
            // produces a text block, even an empty one.
            var warn = log.atWarn()
                    .addKeyValue("source", "tribute-llm")
                    .addKeyValue("artist", artistName);
            Object stopReason = response.get("stop_reason");
            if (stopReason != null) {
                warn = warn.addKeyValue("stop_reason", stopReason);
            }
            warn.log("tribute-bands returned no text block");
            return result;
        }

        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            Matcher m = LINE_ITEM.matcher(line.trim());
            result.add(m.matches() ? m.group(1).trim() : line.trim());
        }
        log.atDebug().addKeyValue("source", "tribute-llm").addKeyValue("artist", artistName)
                .addKeyValue("count", result.size()).log("tribute bands request");
        return result;
    }
}
