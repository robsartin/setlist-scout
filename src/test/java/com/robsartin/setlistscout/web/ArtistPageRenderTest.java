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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real /artists and / Thymeleaf templates against a booted context + Postgres,
 * signed in as a test user, and checks multi-tenant isolation. Each test uses a distinct owner
 * so saved data can't leak between methods (no per-test rollback). Runs in CI (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ArtistPageRenderTest {

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

    private void savePending(String owner, String name, ArtistSource source, String discoveredVia, String note) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, note);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    @Test
    void artistsPageRendersBothPendingGroups() throws Exception {
        String owner = "render-groups@example.com";
        savePending(owner, "Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION,
                "Tom Petty and the Heartbreakers", "tribute/cover act for Tom Petty and the Heartbreakers");
        savePending(owner, "The Milk Carton Kids", ArtistSource.SIMILAR_EXPANSION,
                "Dawes", "similar to Dawes (single-source match)");

        mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Cover / tribute acts")))
                .andExpect(content().string(containsString("Damn the Torpedoes")))
                .andExpect(content().string(containsString("Members")))
                .andExpect(content().string(containsString("Approve all remaining (2)")))
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                .andExpect(content().string(containsString(">Shows<")))
                .andExpect(content().string(containsString("id=\"active-section\"")))
                .andExpect(content().string(containsString("id=\"pending-section\"")));
    }

    @Test
    void approveAllHtmxReturnsBarePendingSection() throws Exception {
        String owner = "render-approve-all@example.com";
        savePending(owner, "Some Pending Act", ArtistSource.SIMILAR_EXPANSION, "Dawes", "similar to Dawes");

        String res = mockMvc.perform(post("/artists/approve-all-pending").header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(res).contains("pending-section");
        org.assertj.core.api.Assertions.assertThat(res).doesNotContain("<head").doesNotContain("topbar");
    }

    @Test
    void showsPageRendersZipLocationForm() throws Exception {
        // First visit provisions this user's settings with the default ZIP (78701).
        mockMvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", "render-shows@example.com"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Near ZIP")))
                .andExpect(content().string(containsString("78701")));
    }

    @Test
    void artistsAreIsolatedByOwner() throws Exception {
        savePending("alice@example.com", "Alice Only Act", ArtistSource.SIMILAR_EXPANSION, "Dawes", "alice's");
        savePending("bob@example.com", "Bob Only Act", ArtistSource.SIMILAR_EXPANSION, "Dawes", "bob's");

        mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alice Only Act")))
                .andExpect(content().string(not(containsString("Bob Only Act"))));
    }
}
