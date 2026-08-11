package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;
import com.robsartin.setlistscout.domain.Show;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

    private static final Logger log = LoggerFactory.getLogger(BandsintownService.class);

    private final RestClient restClient;
    private final String appId;

    @Autowired
    public BandsintownService(AppProperties props) {
        this(props, "https://rest.bandsintown.com");
    }

    /** Test seam: points at a local stub server instead of the real Bandsintown API. */
    BandsintownService(AppProperties props, String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.appId = props.apis().bandsintownAppId();
    }

    @SuppressWarnings("unchecked")
    public List<Show> searchShows(String artistName, Double latitude, Double longitude,
                                   int radiusMiles, LocalDateTime start, LocalDateTime end) {
        List<Show> shows = new ArrayList<>();

        // Build via a URI template variable rather than pre-encoding + string
        // concatenation -- letting the client encode artistName itself avoids
        // double-encoding (e.g. a pre-encoded "%20" becoming "%2520").
        List<Map<String, Object>> events;
        try {
            events = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/artists/{artistName}/events")
                            .queryParam("app_id", appId)
                            .queryParam("date", "upcoming")
                            .build(artistName))
                    .retrieve()
                    .body(List.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "bandsintown")
                    .addKeyValue("artist", artistName)
                    .log("show search failed");
            events = List.of();
        }

        if (events == null) return shows;

        for (Map<String, Object> event : events) {
            Show show = parseEvent(artistName, event);
            if (show == null || show.getEventDateTime() == null) continue;
            if (show.getEventDateTime().isBefore(start) || show.getEventDateTime().isAfter(end)) continue;
            // Bandsintown has no server-side geo filter (ADR-0003), so cut its worldwide
            // results down to the saved radius by distance from the search origin (ADR-0018).
            // With no origin coordinates (geocode failed), fall back to keeping all in-window shows.
            if (latitude != null && longitude != null) {
                double[] coords = venueCoordinates(event);
                if (coords == null) continue; // no venue coordinates -> can't confirm it's in range
                if (GeoDistance.milesBetween(latitude, longitude, coords[0], coords[1]) > radiusMiles) {
                    continue;
                }
            }
            shows.add(show);
        }
        log.atDebug().addKeyValue("source", "bandsintown").addKeyValue("artist", artistName)
                .addKeyValue("count", shows.size()).log("show search");
        return shows;
    }

    @SuppressWarnings("unchecked")
    private double[] venueCoordinates(Map<String, Object> event) {
        Map<String, Object> venue = (Map<String, Object>) event.get("venue");
        if (venue == null) return null;
        Object lat = venue.get("latitude");
        Object lon = venue.get("longitude");
        if (lat == null || lon == null) return null;
        try {
            return new double[]{Double.parseDouble(lat.toString()), Double.parseDouble(lon.toString())};
        } catch (NumberFormatException e) {
            return null;
        }
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
