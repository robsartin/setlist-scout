package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.service.TestAppProperties;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DiscogsServiceTest {

    private MockWebServer server;
    private DiscogsService service;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        service = new DiscogsService(TestAppProperties.withKeys(), server.url("/").toString());
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private static MockResponse json(String body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body);
    }

    @Test
    @DisplayName("should merge members, groups, and aliases from the artist detail")
    void shouldMergeMembersGroupsAndAliases() {
        server.enqueue(json("""
                {"results": [{"id": 123}]}
                """));
        server.enqueue(json("""
                {
                  "members": [{"name": "Member One"}],
                  "groups": [{"name": "Group One"}],
                  "aliases": [{"name": "Alias One"}]
                }
                """));

        List<String> related = service.findRelatedArtists("Some Band");

        assertThat(related).containsExactlyInAnyOrder("Member One", "Group One", "Alias One");
    }

    @Test
    @DisplayName("should return an empty list when the search finds no results")
    void shouldReturnEmptyWhenNoSearchResults() {
        server.enqueue(json("""
                {"results": []}
                """));

        List<String> related = service.findRelatedArtists("Unknown Band");

        assertThat(related).isEmpty();
    }

    @Test
    @DisplayName("should tolerate a detail response missing all three name lists")
    void shouldTolerateMissingFields() {
        server.enqueue(json("""
                {"results": [{"id": 123}]}
                """));
        server.enqueue(json("""
                {}
                """));

        List<String> related = service.findRelatedArtists("Some Band");

        assertThat(related).isEmpty();
    }
}
