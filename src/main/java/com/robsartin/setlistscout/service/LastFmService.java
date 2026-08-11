package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Taste-based similarity from Last.fm's listener/tag data (artist.getSimilar).
 * Free API key: https://www.last.fm/api/account/create
 */
@Service
public class LastFmService {

    private final RestClient restClient;

    @Autowired
    public LastFmService(AppProperties props) {
        this(props, "https://ws.audioscrobbler.com/2.0");
    }

    /** Test seam: points at a local stub server instead of the real Last.fm API. */
    LastFmService(AppProperties props, String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = props.apis().lastFmApiKey();
    }

    private final String apiKey;

    @SuppressWarnings("unchecked")
    public List<String> findSimilarArtists(String artistName, int limit) {
        List<String> similar = new ArrayList<>();

        Map<String, Object> response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .queryParam("method", "artist.getsimilar")
                            .queryParam("artist", artistName)
                            .queryParam("api_key", apiKey)
                            .queryParam("format", "json")
                            .queryParam("limit", limit)
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            response = Map.of();
        }

        if (response == null) return similar;
        Map<String, Object> similarArtists = (Map<String, Object>) response.get("similarartists");
        if (similarArtists == null) return similar;
        List<Map<String, Object>> artists = (List<Map<String, Object>>) similarArtists.get("artist");
        if (artists == null) return similar;

        for (Map<String, Object> artist : artists) {
            String name = (String) artist.get("name");
            if (name != null) similar.add(name);
        }
        return similar;
    }
}
