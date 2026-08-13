package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real /artists/rejected Thymeleaf template against a booted context + Postgres,
 * signed in as a test user, and checks multi-tenant isolation. Each test uses a distinct owner
 * so saved data can't leak between methods (no per-test rollback). Runs in CI (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class RejectedPageRenderTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-client-secret");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    private void saveWithStatus(String owner, String name, ArtistSource source, String discoveredVia,
                                String note, ArtistStatus status) {
        Artist artist = new Artist(name, source, status, discoveredVia, note);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    @Test
    void rejectedPageRendersRejectedArtistsAndNav() throws Exception {
        String owner = "render-rejected@example.com";
        saveWithStatus(owner, "The Milk Carton Kids", ArtistSource.SIMILAR_EXPANSION, "Dawes",
                "similar to Dawes (single-source match)", ArtistStatus.REJECTED);
        saveWithStatus(owner, "Still Pending Act", ArtistSource.SIMILAR_EXPANSION, "Dawes",
                "similar to Dawes", ArtistStatus.PENDING_REVIEW);

        mockMvc.perform(get("/artists/rejected").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                .andExpect(content().string(containsString("The Milk Carton Kids")))
                .andExpect(content().string(containsString("/artists/")))
                .andExpect(content().string(containsString("/unreject")))
                .andExpect(content().string(containsString("Candidates")))
                .andExpect(content().string(containsString("class=\"count\"")));
    }

    @Test
    void rejectedArtistsAreIsolatedByOwner() throws Exception {
        saveWithStatus("alice-rejected@example.com", "Alice Rejected Act", ArtistSource.SIMILAR_EXPANSION,
                "Dawes", "alice's", ArtistStatus.REJECTED);
        saveWithStatus("bob-rejected@example.com", "Bob Rejected Act", ArtistSource.SIMILAR_EXPANSION,
                "Dawes", "bob's", ArtistStatus.REJECTED);

        mockMvc.perform(get("/artists/rejected")
                        .with(oidcLogin().idToken(t -> t.claim("email", "alice-rejected@example.com"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alice Rejected Act")))
                .andExpect(content().string(not(containsString("Bob Rejected Act"))));
    }
}
