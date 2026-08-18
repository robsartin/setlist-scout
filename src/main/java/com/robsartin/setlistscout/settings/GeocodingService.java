package com.robsartin.setlistscout.settings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a US ZIP code -- or a city/state pair -- into lat/long (+ city/state) via
 * Zippopotam.us, a free, no-key geocoder (https://api.zippopotam.us). {@link #geocode}
 * backs the owner's ZIP-based search location: Ticketmaster sends this lat/long as a
 * {@code geoPoint} geohash (falling back to the bare ZIP only if geocoding failed -- #152),
 * and Bandsintown -- which has no server-side geo filter -- filters its results by distance
 * from this lat/long (see ADR-0018). {@link #geocodeCity} backs the same distance filtering for band-site
 * scraped shows (#28), which arrive as a bare city name rather than a ZIP -- successful
 * lookups are cached in-process (keyed by state+city) since the same venue city recurs
 * across scans; failed/empty lookups are deliberately NOT cached so a transient error
 * doesn't permanently downgrade a real city to the old city-name fallback for the life of
 * the process -- it's simply retried on the next scan. Degrades to empty on any error so a
 * geocode failure never breaks a scan.
 */
@Service
public class GeocodingService {

    private static final Logger log = LoggerFactory.getLogger(GeocodingService.class);

    private final RestClient restClient;
    private final Map<String, Optional<GeoResult>> cityCache = new ConcurrentHashMap<>();

    /**
     * The base URL is injectable (#184) rather than hardcoded so the test suite can point it at an
     * unroutable address (see {@code src/test/resources/application.properties}) instead of making
     * live calls to Zippopotam.us on every {@code @SpringBootTest} that touches an owner's settings
     * -- {@link #fetch} degrades to empty on any error, so an unreachable URL is safe by
     * construction. Also doubles as the test seam a plain unit test (e.g. {@code
     * GeocodingServiceTest}) uses to point at a local {@code MockWebServer} directly, bypassing
     * Spring entirely.
     */
    @Autowired
    public GeocodingService(@Value("${setlistscout.geocoding.base-url:https://api.zippopotam.us}") String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public Optional<GeoResult> geocode(String zip) {
        Map<String, Object> response = fetch("/us/{zip}", zip, "zip", zip);
        return firstPlace(response, "zip", zip);
    }

    /**
     * Geocodes a city within a US state (e.g. "Round Rock", "TX") to lat/long. Unlike
     * {@link #geocode}, a successful result is cached for the life of this service instance
     * -- a scraped band-site show's venue city repeats across scans, and Zippopotam.us has
     * no rate-limit budget worth spending on the same lookup twice. A failed/empty lookup
     * (a genuine unknown city, or a transient error -- {@link #fetch} can't tell them apart)
     * is deliberately never cached, so it's retried on the next scan rather than permanently
     * pinning that city to the old city-name fallback.
     */
    public Optional<GeoResult> geocodeCity(String city, String state) {
        if (city == null || city.isBlank() || state == null || state.isBlank()) {
            return Optional.empty();
        }
        String cacheKey = state.trim().toLowerCase() + "|" + city.trim().toLowerCase();
        Optional<GeoResult> cached = cityCache.get(cacheKey);
        if (cached != null) return cached;

        Map<String, Object> response = fetch("/us/{state}/{city}", city, "city", state, city);
        Optional<GeoResult> result = firstPlace(response, "city", city);
        if (result.isPresent()) {
            cityCache.put(cacheKey, result);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetch(String uriTemplate, String logKey, String logField, Object... uriVars) {
        try {
            return restClient.get()
                    .uri(uriTemplate, uriVars)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "geocoding")
                    .addKeyValue(logField, logKey)
                    .log("geocode request failed");
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private Optional<GeoResult> firstPlace(Map<String, Object> response, String logField, String logKey) {
        if (response == null) return Optional.empty();
        List<Map<String, Object>> places = (List<Map<String, Object>>) response.get("places");
        if (places == null || places.isEmpty()) return Optional.empty();

        Map<String, Object> place = places.get(0);
        try {
            double latitude = Double.parseDouble((String) place.get("latitude"));
            double longitude = Double.parseDouble((String) place.get("longitude"));
            String city = (String) place.get("place name");
            String state = (String) place.get("state abbreviation");
            Optional<GeoResult> result = Optional.of(new GeoResult(latitude, longitude, city, state));
            log.atDebug().addKeyValue("source", "geocoding").addKeyValue(logField, logKey)
                    .addKeyValue("found", result.isPresent()).log("geocode lookup");
            return result;
        } catch (NumberFormatException | NullPointerException e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "geocoding")
                    .addKeyValue(logField, logKey)
                    .log("geocode place parse failed");
            return Optional.empty();
        }
    }

    public record GeoResult(double latitude, double longitude, String city, String state) {}
}
