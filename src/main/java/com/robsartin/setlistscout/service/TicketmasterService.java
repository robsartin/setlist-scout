package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;
import com.robsartin.setlistscout.domain.Show;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

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

    private static final Logger log = LoggerFactory.getLogger(TicketmasterService.class);

    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    public TicketmasterService(AppProperties props) {
        this(props, "https://app.ticketmaster.com/discovery/v2");
    }

    /** Test seam: points at a local stub server instead of the real Ticketmaster API. */
    TicketmasterService(AppProperties props, String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = props.apis().ticketmasterApiKey();
    }

    @SuppressWarnings("unchecked")
    public List<Show> searchShows(String artistName, String postalCode,
                                   int radiusMiles, LocalDateTime start, LocalDateTime end) {
        List<Show> shows = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

        Map<String, Object> response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/events.json")
                            .queryParam("apikey", apiKey)
                            .queryParam("keyword", artistName)
                            .queryParam("postalCode", postalCode)
                            .queryParam("radius", radiusMiles)
                            .queryParam("unit", "miles")
                            .queryParam("startDateTime", start.format(fmt))
                            .queryParam("endDateTime", end.format(fmt))
                            .queryParam("classificationName", "music")
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "ticketmaster")
                    .addKeyValue("artist", artistName)
                    .log("show search failed");
            response = Map.of();
        }

        if (response == null) return shows;
        Map<String, Object> embedded = (Map<String, Object>) response.get("_embedded");
        if (embedded == null) return shows;
        List<Map<String, Object>> events = (List<Map<String, Object>>) embedded.get("events");
        if (events == null) return shows;

        for (Map<String, Object> event : events) {
            if (!hasMatchingAttraction(artistName, event)) continue;
            shows.add(parseEvent(artistName, event));
        }
        log.atDebug().addKeyValue("source", "ticketmaster").addKeyValue("artist", artistName)
                .addKeyValue("count", shows.size()).log("show search");
        return shows;
    }

    /**
     * Ticketmaster's {@code keyword} query is a fuzzy relevance search, so it
     * can return events that only loosely resemble the searched artist. Only
     * trust an event if the searched artist is actually one of the billed
     * attractions; events with no attractions at all can't be confirmed either
     * way, so they're dropped too.
     */
    @SuppressWarnings("unchecked")
    private boolean hasMatchingAttraction(String artistName, Map<String, Object> event) {
        Map<String, Object> embedded = (Map<String, Object>) event.get("_embedded");
        List<Map<String, Object>> attractions = embedded != null
                ? (List<Map<String, Object>>) embedded.get("attractions") : null;
        if (attractions == null) return false;

        String normalizedSearch = artistName.trim().toLowerCase();
        for (Map<String, Object> attraction : attractions) {
            if (attraction.get("name") instanceof String name
                    && name.trim().toLowerCase().equals(normalizedSearch)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private Show parseEvent(String artistName, Map<String, Object> event) {
        Map<String, Object> dates = (Map<String, Object>) event.get("dates");
        Map<String, Object> start = dates != null ? (Map<String, Object>) dates.get("start") : null;
        LocalDateTime eventDateTime = parseStartDateTime(start);

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

    /**
     * Builds the show time from Ticketmaster's {@code dates.start} block.
     * <p>
     * The {@code dateTime} field is UTC (trailing Z); parsing it with a local
     * formatter keeps the UTC wall-clock time, so an evening show reads as the
     * small hours of the next morning. Ticketmaster also exposes the correct
     * venue-local wall-clock as {@code localDate} + {@code localTime}, so we
     * prefer those when both are present and only fall back to the UTC
     * {@code dateTime} when the local fields are absent.
     */
    private LocalDateTime parseStartDateTime(Map<String, Object> start) {
        if (start == null) return null;

        String localDate = (String) start.get("localDate");
        String localTime = (String) start.get("localTime");
        if (localDate != null && localTime != null) {
            return LocalDateTime.parse(localDate + "T" + localTime, DateTimeFormatter.ISO_DATE_TIME);
        }

        String dateTimeStr = (String) start.get("dateTime");
        return dateTimeStr != null
                ? LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_DATE_TIME)
                : null;
    }
}
