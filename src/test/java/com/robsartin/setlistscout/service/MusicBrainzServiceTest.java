package com.robsartin.setlistscout.service;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MusicBrainzServiceTest {

    private MockWebServer server;
    private MusicBrainzService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // Near-zero rate-limit delay so this test doesn't pay the real ~1.1s cost.
        service = new MusicBrainzService(TestAppProperties.withKeys(), server.url("/").toString(), 5L);
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("should return related artists, excluding the queried artist itself")
    void shouldReturnRelatedArtistsExcludingSelf() {
        server.enqueue(json("""
                {"artists": [{"id": "mbid-123", "name": "Dawes"}]}
                """));
        server.enqueue(json("""
                {"relations": [
                  {"type": "member of band", "artist": {"name": "Taylor Goldsmith"}},
                  {"type": "member of band", "artist": {"name": "Dawes"}}
                ]}
                """));

        List<String> related = service.findRelatedArtists("Dawes");

        assertThat(related).containsExactly("Taylor Goldsmith");
    }

    @Test
    @DisplayName("should return an empty list when the artist search finds nothing")
    void shouldReturnEmptyWhenArtistNotFound() {
        server.enqueue(json("""
                {"artists": []}
                """));

        List<String> related = service.findRelatedArtists("Unknown Band");

        assertThat(related).isEmpty();
    }

    @Test
    @DisplayName("should return an empty list when the detail lookup has no relations")
    void shouldReturnEmptyWhenNoRelations() {
        server.enqueue(json("""
                {"artists": [{"id": "mbid-123", "name": "Dawes"}]}
                """));
        server.enqueue(json("""
                {}
                """));

        List<String> related = service.findRelatedArtists("Dawes");

        assertThat(related).isEmpty();
    }

    @Test
    @DisplayName("findOfficialHomepage returns the official-homepage url-rel")
    void shouldReturnOfficialHomepage() {
        server.enqueue(json("""
                {"artists": [{"id": "mbid-123", "name": "Dawes"}]}
                """));
        server.enqueue(json("""
                {"relations": [
                  {"type": "wikidata", "url": {"resource": "https://www.wikidata.org/wiki/Q123"}},
                  {"type": "official homepage", "url": {"resource": "https://dawestheband.com"}}
                ]}
                """));

        assertThat(service.findOfficialHomepage("Dawes")).contains("https://dawestheband.com");
    }

    @Test
    @DisplayName("findOfficialHomepage is empty when there is no official-homepage relation")
    void shouldReturnEmptyHomepageWhenNone() {
        server.enqueue(json("""
                {"artists": [{"id": "mbid-123", "name": "Dawes"}]}
                """));
        server.enqueue(json("""
                {"relations": [
                  {"type": "wikidata", "url": {"resource": "https://www.wikidata.org/wiki/Q123"}}
                ]}
                """));

        assertThat(service.findOfficialHomepage("Dawes")).isEmpty();
    }
}
