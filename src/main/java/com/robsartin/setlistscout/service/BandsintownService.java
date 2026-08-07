package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;
import com.robsartin.setlistscout.domain.Show;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * https://app.bandsintown.com/api/authenticationdocs -- free "app_id" registration.
 * Bandsintown's events endpoint returns upcoming shows per-artist; it doesn't filter
 * by radius server-side, so filtering to "near Austin" happens client-side here.
 * Good complement to Ticketmaster -- catches smaller club shows Ticketmaster misses.
 */
@Service
public class BandsintownService {

    private final WebClient webClient;
    private final String appId;

    public BandsintownService(AppProperties props) {
        this.webClient = WebClient.builder()
                .baseUrl("https://rest.bandsintown.com")
                .build();
        this.appId = props.apis().bandsintownAppId();
    }

    @SuppressWarnings("unchecked")
    public List<Show> searchShows(String artistName, String cityFilter, String stateFilter,
                                   LocalDateTime start, LocalDateTime end) {
        List<Show> shows = new ArrayList<>();
        String encodedName = UriUtils.encodePathSegment(artistName, StandardCharsets.UTF_8);

        List<Map<String, Object>> events = webClient.get()
                .uri("/artists/" + encodedName + "/events?app_id=" + appId + "&date=upcoming")
                .retrieve()
                .bodyToMono(List.class)
                .onErrorReturn(List.of())
                .block();

        if (events == null) return shows;

        for (Map<String, Object> event : events) {
            Show show = parseEvent(artistName, event);
            if (show == null || show.getEventDateTime() == null) continue;
            if (show.getEventDateTime().isBefore(start) || show.getEventDateTime().isAfter(end)) continue;
            // client-side city/state filter -- Bandsintown has no server-side radius/geo
            // filter (see ADR-0003), so its results, which are the artist's shows worldwide,
            // must be cut down to the saved location here.
            if (cityFilter != null && show.getVenueCity() != null
                    && !show.getVenueCity().equalsIgnoreCase(cityFilter)) {
                continue; // caller can loosen this by passing cityFilter=null for a wider net
            }
            String region = venueRegion(event); // Bandsintown's per-event state/province
            if (stateFilter != null && region != null
                    && !region.equalsIgnoreCase(stateFilter)) {
                continue; // wrong state -- without this, out-of-state shows are persisted too
            }
            shows.add(show);
        }
        return shows;
    }

    @SuppressWarnings("unchecked")
    private String venueRegion(Map<String, Object> event) {
        Map<String, Object> venue = (Map<String, Object>) event.get("venue");
        return venue != null ? (String) venue.get("region") : null;
    }

    @SuppressWarnings("unchecked")
    private Show parseEvent(String artistName, Map<String, Object> event) {
        String datetimeStr = (String) event.get("datetime");
        LocalDateTime eventDateTime = datetimeStr != null
                ? LocalDateTime.parse(datetimeStr, DateTimeFormatter.ISO_DATE_TIME)
                : null;

        Map<String, Object> venue = (Map<String, Object>) event.get("venue");
        String venueName = venue != null ? (String) venue.get("name") : "Unknown venue";
        String venueCity = venue != null ? (String) venue.get("city") : null;

        String url = (String) event.get("url");

        return new Show(artistName, eventDateTime, venueName, venueCity, null, "bandsintown", url);
    }
}
