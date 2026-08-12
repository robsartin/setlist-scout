package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LLM fallback for the band-site scraper (#22): when a tour page has no structured JSON-LD
 * events, ask Claude to extract shows from the page text. Returns each show as a
 * (date, venue, city) triple. Degrades to empty on any error so a scrape never breaks a scan.
 */
@Service
public class TourPageLlmService {

    private static final Logger log = LoggerFactory.getLogger(TourPageLlmService.class);

    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    public TourPageLlmService(AppProperties props) {
        this(props, "https://api.anthropic.com/v1");
    }

    /** Test seam: points at a local stub server instead of the real Anthropic API. */
    TourPageLlmService(AppProperties props, String baseUrl) {
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
        String text = pageText.length() > 8000 ? pageText.substring(0, 8000) : pageText;
        String prompt = "This is the text of a tour/shows page for the band \"" + artistName + "\"."
                + " Extract each upcoming live show as one line in exactly this format:\n"
                + "YYYY-MM-DD | Venue name | City\n"
                + "One show per line, no header, no commentary. If there are no shows, return nothing.\n\n"
                + text;

        Map<String, Object> body = Map.of(
                "model", "claude-sonnet-5",
                "max_tokens", 1000,
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
        String responseText = (String) content.get(0).get("text");
        if (responseText == null) return result;

        for (String line : responseText.split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            try {
                LocalDate date = LocalDate.parse(parts[0].trim());
                String venue = parts[1].trim();
                String city = parts[2].trim();
                if (!venue.isBlank()) {
                    result.add(new ExtractedShow(date, venue, city));
                }
            } catch (Exception e) {
                // skip lines that aren't a well-formed "date | venue | city"
            }
        }
        log.atDebug().addKeyValue("source", "tour-llm").addKeyValue("artist", artistName)
                .addKeyValue("count", result.size()).log("tour-page extraction");
        return result;
    }

    public record ExtractedShow(LocalDate date, String venue, String city) {}
}
