package com.robsartin.setlistscout.shared;

import com.robsartin.setlistscout.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

    private static final Logger log = LoggerFactory.getLogger(MusicBrainzService.class);

    private final RestClient restClient;
    private final long rateLimitMillis;

    @Autowired
    public MusicBrainzService(AppProperties props) {
        this(props, "https://musicbrainz.org/ws/2", 1100);
    }

    /** Test seam: local stub server and a near-zero rate-limit delay. */
    public MusicBrainzService(AppProperties props, String baseUrl, long rateLimitMillis) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, props.apis().musicBrainzUserAgent())
                .build();
        this.rateLimitMillis = rateLimitMillis;
    }

    /** Returns related artist/band names (members, ex-members, collaborators, solo projects). */
    @SuppressWarnings("unchecked")
    public List<String> findRelatedArtists(String artistName) {
        List<String> related = new ArrayList<>();

        Map<String, Object> searchResult;
        try {
            searchResult = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/artist/")
                            .queryParam("query", "artist:\"" + artistName + "\"")
                            .queryParam("fmt", "json")
                            .queryParam("limit", 1)
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "musicbrainz")
                    .addKeyValue("artist", artistName)
                    .log("artist search failed");
            searchResult = Map.of();
        }

        if (searchResult == null) return related;
        List<Map<String, Object>> artists = (List<Map<String, Object>>) searchResult.get("artists");
        if (artists == null || artists.isEmpty()) return related;

        String mbid = (String) artists.get(0).get("id");
        sleepForRateLimit();

        Map<String, Object> detail;
        try {
            detail = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/artist/" + mbid)
                            .queryParam("inc", "artist-rels")
                            .queryParam("fmt", "json")
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "musicbrainz")
                    .addKeyValue("artist", artistName)
                    .log("related-artists lookup failed");
            detail = Map.of();
        }

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
        log.atDebug().addKeyValue("source", "musicbrainz").addKeyValue("artist", artistName)
                .addKeyValue("count", related.size()).log("related artists lookup");
        return related;
    }

    /** The artist's official homepage URL from MusicBrainz's url-rels, if one is recorded. */
    @SuppressWarnings("unchecked")
    public java.util.Optional<String> findOfficialHomepage(String artistName) {
        Map<String, Object> searchResult;
        try {
            searchResult = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/artist/")
                            .queryParam("query", "artist:\"" + artistName + "\"")
                            .queryParam("fmt", "json")
                            .queryParam("limit", 1)
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "musicbrainz")
                    .addKeyValue("artist", artistName)
                    .log("artist search failed");
            searchResult = Map.of();
        }

        if (searchResult == null) return java.util.Optional.empty();
        List<Map<String, Object>> artists = (List<Map<String, Object>>) searchResult.get("artists");
        if (artists == null || artists.isEmpty()) return java.util.Optional.empty();

        String mbid = (String) artists.get(0).get("id");
        sleepForRateLimit();

        Map<String, Object> detail;
        try {
            detail = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/artist/" + mbid)
                            .queryParam("inc", "url-rels")
                            .queryParam("fmt", "json")
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "musicbrainz")
                    .addKeyValue("artist", artistName)
                    .log("official homepage lookup failed");
            detail = Map.of();
        }

        if (detail == null) return java.util.Optional.empty();
        List<Map<String, Object>> relations = (List<Map<String, Object>>) detail.get("relations");
        if (relations == null) return java.util.Optional.empty();

        for (Map<String, Object> rel : relations) {
            if ("official homepage".equals(rel.get("type"))) {
                Map<String, Object> url = (Map<String, Object>) rel.get("url");
                if (url != null && url.get("resource") instanceof String resource) {
                    java.util.Optional<String> result = java.util.Optional.of(resource);
                    log.atDebug().addKeyValue("source", "musicbrainz").addKeyValue("artist", artistName)
                            .addKeyValue("found", result.isPresent()).log("official homepage lookup");
                    return result;
                }
            }
        }
        java.util.Optional<String> result = java.util.Optional.empty();
        log.atDebug().addKeyValue("source", "musicbrainz").addKeyValue("artist", artistName)
                .addKeyValue("found", result.isPresent()).log("official homepage lookup");
        return result;
    }

    private void sleepForRateLimit() {
        try {
            Thread.sleep(rateLimitMillis); // stay under MusicBrainz's ~1 req/sec limit
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
