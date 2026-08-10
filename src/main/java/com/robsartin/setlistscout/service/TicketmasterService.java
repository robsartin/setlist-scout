package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;
import com.robsartin.setlistscout.domain.Show;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * https://developer.ticketmaster.com/products-and-docs/apis/discovery-api/v2/
 * Free tier: 5000 calls/day, ~5 req/sec. Filters by city + state + radius, which
 * covers the "near Austin, adjustable radius" requirement without needing a
 * separate geocoding step.
 */
@Service
public class TicketmasterService {

    private final WebClient webClient;
    private final String apiKey;

    @Autowired
    public TicketmasterService(AppProperties props) {
        this(props, "https://app.ticketmaster.com/discovery/v2");
    }

    /** Test seam: points at a local stub server instead of the real Ticketmaster API. */
    TicketmasterService(AppProperties props, String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
        this.apiKey = props.apis().ticketmasterApiKey();
    }

    @SuppressWarnings("unchecked")
    public List<Show> searchShows(String artistName, String city, String stateCode,
                                   int radiusMiles, LocalDateTime start, LocalDateTime end) {
        List<Show> shows = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

        Map<String, Object> response = webClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/events.json")
                        .queryParam("apikey", apiKey)
                        .queryParam("keyword", artistName)
                        .queryParam("city", city)
                        .queryParam("stateCode", stateCode)
                        .queryParam("radius", radiusMiles)
                        .queryParam("unit", "miles")
                        .queryParam("startDateTime", start.format(fmt))
                        .queryParam("endDateTime", end.format(fmt))
                        .queryParam("classificationName", "music")
                        .build())
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of())
                .block();

        if (response == null) return shows;
        Map<String, Object> embedded = (Map<String, Object>) response.get("_embedded");
        if (embedded == null) return shows;
        List<Map<String, Object>> events = (List<Map<String, Object>>) embedded.get("events");
        if (events == null) return shows;

        for (Map<String, Object> event : events) {
            shows.add(parseEvent(artistName, event));
        }
        return shows;
    }

    @SuppressWarnings("unchecked")
    private Show parseEvent(String artistName, Map<String, Object> event) {
        Map<String, Object> dates = (Map<String, Object>) event.get("dates");
        Map<String, Object> start = dates != null ? (Map<String, Object>) dates.get("start") : null;
        String dateTimeStr = start != null ? (String) start.get("dateTime") : null;
        LocalDateTime eventDateTime = dateTimeStr != null
                ? LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_DATE_TIME)
                : null;

        Map<String, Object> embedded = (Map<String, Object>) event.get("_embedded");
        List<Map<String, Object>> venues = embedded != null ? (List<Map<String, Object>>) embedded.get("venues") : null;
        Map<String, Object> venue = (venues != null && !venues.isEmpty()) ? venues.get(0) : Map.of();
        String venueName = (String) venue.getOrDefault("name", "Unknown venue");
        Map<String, Object> cityMap = (Map<String, Object>) venue.get("city");
        String venueCity = cityMap != null ? (String) cityMap.get("name") : null;

        List<Map<String, Object>> priceRanges = (List<Map<String, Object>>) event.get("priceRanges");
        BigDecimal price = null;
        if (priceRanges != null && !priceRanges.isEmpty()) {
            Object min = priceRanges.get(0).get("min");
            if (min instanceof Number n) price = BigDecimal.valueOf(n.doubleValue());
        }

        String url = (String) event.get("url");

        return new Show(artistName, eventDateTime, venueName, venueCity, price, "ticketmaster", url);
    }
}
