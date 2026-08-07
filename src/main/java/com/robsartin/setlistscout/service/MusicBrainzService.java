package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Looks up "member of band" / "collaborator" relationships via the MusicBrainz API.
 * MusicBrainz requires a descriptive User-Agent identifying the application and a
 * contact -- see https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting.
 * Rate limit: ~1 request/second for unauthenticated use, so calls here are sequential
 * on purpose -- don't parallelize without adding a rate limiter.
 */
@Service
public class MusicBrainzService {

    private final WebClient webClient;

    public MusicBrainzService(AppProperties props) {
        this.webClient = WebClient.builder()
                .baseUrl("https://musicbrainz.org/ws/2")
                .defaultHeader(HttpHeaders.USER_AGENT, props.apis().musicBrainzUserAgent())
                .build();
    }

    /** Returns related artist/band names (members, ex-members, collaborators, solo projects). */
    @SuppressWarnings("unchecked")
    public List<String> findRelatedArtists(String artistName) {
        List<String> related = new ArrayList<>();

        Map<String, Object> searchResult = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/artist/")
                        .queryParam("query", "artist:\"" + artistName + "\"")
                        .queryParam("fmt", "json")
                        .queryParam("limit", 1)
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of())
                .block();

        if (searchResult == null) return related;
        List<Map<String, Object>> artists = (List<Map<String, Object>>) searchResult.get("artists");
        if (artists == null || artists.isEmpty()) return related;

        String mbid = (String) artists.get(0).get("id");
        sleepForRateLimit();

        Map<String, Object> detail = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/artist/" + mbid)
                        .queryParam("inc", "artist-rels")
                        .queryParam("fmt", "json")
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of())
                .block();

        if (detail == null) return related;
        List<Map<String, Object>> relations = (List<Map<String, Object>>) detail.get("relations");
        if (relations == null) return related;

        for (Map<String, Object> rel : relations) {
            String type = (String) rel.get("type"); // e.g. "member of band", "collaboration"
            Map<String, Object> otherArtist = (Map<String, Object>) rel.get("artist");
            if (otherArtist == null) continue;
            String name = (String) otherArtist.get("name");
            if (name != null && !name.equalsIgnoreCase(artistName)) {
                related.add(name);
            }
        }
        return related;
    }

    private void sleepForRateLimit() {
        try {
            Thread.sleep(1100); // stay under MusicBrainz's ~1 req/sec limit
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
