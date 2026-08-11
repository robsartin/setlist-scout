package com.robsartin.setlistscout.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a US ZIP code into lat/long (+ city/state) via Zippopotam.us -- a free, no-key
 * geocoder (https://api.zippopotam.us). Used so the search location can be entered as a
 * ZIP: Ticketmaster takes the ZIP directly, but Bandsintown has no geo filter, so its
 * results are filtered by distance from this lat/long (see ADR-0018). Degrades to empty
 * on any error so a geocode failure never breaks a scan.
 */
@Service
public class GeocodingService {

    private final WebClient webClient;

    @Autowired
    public GeocodingService() {
        this("https://api.zippopotam.us");
    }

    /** Test seam: points at a local stub server instead of the real Zippopotam.us API. */
    GeocodingService(String baseUrl) {
        this.webClient = WebClient.builder().baseUrl(baseUrl).build();
    }

    @SuppressWarnings("unchecked")
    public Optional<GeoResult> geocode(String zip) {
        Map<String, Object> response = webClient.get()
                .uri("/us/{zip}", zip)
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of())
                .block();

        if (response == null) return Optional.empty();
        List<Map<String, Object>> places = (List<Map<String, Object>>) response.get("places");
        if (places == null || places.isEmpty()) return Optional.empty();

        Map<String, Object> place = places.get(0);
        try {
            double latitude = Double.parseDouble((String) place.get("latitude"));
            double longitude = Double.parseDouble((String) place.get("longitude"));
            String city = (String) place.get("place name");
            String state = (String) place.get("state abbreviation");
            return Optional.of(new GeoResult(latitude, longitude, city, state));
        } catch (NumberFormatException | NullPointerException e) {
            return Optional.empty();
        }
    }

    public record GeoResult(double latitude, double longitude, String city, String state) {}
}
