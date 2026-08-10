package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

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

    private final WebClient webClient;
    private final String apiKey;
    private static final Pattern LINE_ITEM = Pattern.compile("^\\s*[-\\d.]+\\s*[).]?\\s*(.+)$");

    public TributeLlmService(AppProperties props) {
        this(props, "https://api.anthropic.com/v1");
    }

    /** Test seam: points at a local stub server instead of the real Anthropic API. */
    TributeLlmService(AppProperties props, String baseUrl) {
        this.webClient = WebClient.builder()
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
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        Map<String, Object> response = webClient.post()
                .uri("/messages")
                .header("x-api-key", apiKey)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of())
                .block();

        if (response == null) return result;
        List<Map<String, Object>> content = (List<Map<String, Object>>) response.get("content");
        if (content == null || content.isEmpty()) return result;

        String text = (String) content.get(0).get("text");
        if (text == null) return result;

        for (String line : text.split("\n")) {
            if (line.isBlank()) continue;
            Matcher m = LINE_ITEM.matcher(line.trim());
            result.add(m.matches() ? m.group(1).trim() : line.trim());
        }
        return result;
    }
}
