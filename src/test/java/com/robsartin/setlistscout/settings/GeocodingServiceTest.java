package com.robsartin.setlistscout.settings;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class GeocodingServiceTest {

    private MockWebServer server;
    private GeocodingService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new GeocodingService(server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("parses lat/long, city, and state abbreviation from a Zippopotam response")
    void parsesGeocodeResult() {
        server.enqueue(json("""
                {
                  "post code": "78701",
                  "places": [
                    {"place name": "Austin", "longitude": "-97.7431", "state": "Texas",
                     "state abbreviation": "TX", "latitude": "30.2672"}
                  ]
                }
                """));

        Optional<GeocodingService.GeoResult> result = service.geocode("78701");

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isEqualTo(30.2672);
        assertThat(result.get().longitude()).isEqualTo(-97.7431);
        assertThat(result.get().city()).isEqualTo("Austin");
        assertThat(result.get().state()).isEqualTo("TX");
    }

    @Test
    @DisplayName("returns empty for an unknown ZIP (404)")
    void emptyOnNotFound() {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThat(service.geocode("00000")).isEmpty();
    }

    @Test
    @DisplayName("returns empty when the response has no places")
    void emptyWhenNoPlaces() {
        server.enqueue(json("{\"post code\": \"78701\"}"));

        assertThat(service.geocode("78701")).isEmpty();
    }

    @Test
    @DisplayName("geocodeCity parses lat/long from a Zippopotam city+state response")
    void geocodesCity() {
        server.enqueue(json("""
                {
                  "places": [
                    {"place name": "Round Rock", "longitude": "-97.6786", "state": "Texas",
                     "state abbreviation": "TX", "latitude": "30.5083"}
                  ]
                }
                """));

        Optional<GeocodingService.GeoResult> result = service.geocodeCity("Round Rock", "TX");

        assertThat(result).isPresent();
        assertThat(result.get().latitude()).isEqualTo(30.5083);
        assertThat(result.get().longitude()).isEqualTo(-97.6786);
    }

    @Test
    @DisplayName("geocodeCity returns empty for an unknown city (404)")
    void geocodeCityEmptyOnNotFound() {
        server.enqueue(new MockResponse().setResponseCode(404));

        assertThat(service.geocodeCity("Nowhereville", "TX")).isEmpty();
    }

    @Test
    @DisplayName("geocodeCity returns empty when city or state is blank")
    void geocodeCityEmptyOnBlankInput() {
        assertThat(service.geocodeCity(null, "TX")).isEmpty();
        assertThat(service.geocodeCity("Austin", null)).isEmpty();
        assertThat(service.geocodeCity("Austin", " ")).isEmpty();
    }

    @Test
    @DisplayName("geocodeCity caches results so the same city+state is only looked up once")
    void geocodeCityCachesResults() throws InterruptedException {
        server.enqueue(json("""
                {
                  "places": [
                    {"place name": "Round Rock", "longitude": "-97.6786", "state": "Texas",
                     "state abbreviation": "TX", "latitude": "30.5083"}
                  ]
                }
                """));

        Optional<GeocodingService.GeoResult> first = service.geocodeCity("Round Rock", "TX");
        Optional<GeocodingService.GeoResult> second = service.geocodeCity("Round Rock", "TX");

        assertThat(first).isEqualTo(second);
        assertThat(server.getRequestCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("geocodeCity does NOT cache a failed/empty lookup -- it's retried next time")
    void geocodeCityDoesNotCacheFailures() {
        server.enqueue(new MockResponse().setResponseCode(404));
        server.enqueue(json("""
                {
                  "places": [
                    {"place name": "Round Rock", "longitude": "-97.6786", "state": "Texas",
                     "state abbreviation": "TX", "latitude": "30.5083"}
                  ]
                }
                """));

        Optional<GeocodingService.GeoResult> first = service.geocodeCity("Round Rock", "TX");
        Optional<GeocodingService.GeoResult> second = service.geocodeCity("Round Rock", "TX");

        assertThat(first).isEmpty();
        assertThat(second).isPresent();
        assertThat(server.getRequestCount()).isEqualTo(2);
    }
}
