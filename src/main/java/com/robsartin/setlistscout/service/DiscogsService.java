package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fills gaps MusicBrainz misses -- especially older/regional acts. Discogs artist
 * records expose "members" (for groups) and "groups"/"aliases" (for individuals).
 * Requires a Discogs personal access token: https://www.discogs.com/settings/developers
 */
@Service
public class DiscogsService {

    private final RestClient restClient;

    @Autowired
    public DiscogsService(AppProperties props) {
        this(props, "https://api.discogs.com");
    }

    /** Test seam: points at a local stub server instead of the real Discogs API. */
    DiscogsService(AppProperties props, String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, "SetlistScout/0.1 (+personal use)")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Discogs token=" + props.apis().discogsToken())
                .build();
    }

    @SuppressWarnings("unchecked")
    public List<String> findRelatedArtists(String artistName) {
        List<String> related = new ArrayList<>();

        Map<String, Object> searchResult;
        try {
            searchResult = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/database/search")
                            .queryParam("q", artistName)
                            .queryParam("type", "artist")
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            searchResult = Map.of();
        }

        if (searchResult == null) return related;
        List<Map<String, Object>> results = (List<Map<String, Object>>) searchResult.get("results");
        if (results == null || results.isEmpty()) return related;

        Object idObj = results.get(0).get("id");
        if (idObj == null) return related;

        Map<String, Object> detail;
        try {
            detail = restClient.get()
                    .uri("/artists/" + idObj)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            detail = Map.of();
        }

        if (detail == null) return related;

        addNames(related, (List<Map<String, Object>>) detail.get("members"));
        addNames(related, (List<Map<String, Object>>) detail.get("groups"));
        addNames(related, (List<Map<String, Object>>) detail.get("aliases"));

        return related;
    }

    private void addNames(List<String> target, List<Map<String, Object>> entries) {
        if (entries == null) return;
        for (Map<String, Object> entry : entries) {
            String name = (String) entry.get("name");
            if (name != null) {
                target.add(name);
            }
        }
    }
}
