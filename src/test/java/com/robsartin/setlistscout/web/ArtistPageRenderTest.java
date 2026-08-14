package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
 *
 * <p>Since PR2 (#96), /artists is active-only (seed + approved); the pending-review queue moved to
 * /artists/candidates, covered by {@link CandidatesPageRenderTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ArtistPageRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    private void saveActive(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    private void savePending(String owner, String name, ArtistSource source, String discoveredVia, String note) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, note);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    @Test
    void artistsPageIsActiveOnly() throws Exception {
        String owner = "render-active-only@example.com";
        saveActive(owner, "The Heartbreakers", ArtistStatus.SEED);
        saveActive(owner, "Wilco", ArtistStatus.APPROVED);
        savePending(owner, "Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION,
                "Tom Petty and the Heartbreakers", "tribute/cover act for Tom Petty and the Heartbreakers");

        mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The Heartbreakers")))
                .andExpect(content().string(containsString("Wilco")))
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))
                .andExpect(content().string(containsString(">Shows<")))
                .andExpect(content().string(containsString("id=\"active-section\"")))
                // Pending review moved to /artists/candidates -- none of it renders here anymore.
                .andExpect(content().string(not(containsString("Pending review"))))
                .andExpect(content().string(not(containsString("pending-section"))))
                .andExpect(content().string(not(containsString("Damn the Torpedoes"))))
                .andExpect(content().string(not(containsString("Run expansion now"))))
                .andExpect(content().string(not(containsString("Why it was suggested"))))
                .andExpect(content().string(not(containsString("unreject"))));
    }

    @Test
    void approveAllHtmxReturnsBareCandidatesGlobalBar() throws Exception {
        String owner = "render-approve-all@example.com";
        savePending(owner, "Some Pending Act", ArtistSource.SIMILAR_EXPANSION, "Dawes", "similar to Dawes");

        String res = mockMvc.perform(post("/artists/approve-all-pending").header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(res).contains("global-bar");
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
        saveActive("alice@example.com", "Alice Only Act", ArtistStatus.SEED);
        saveActive("bob@example.com", "Bob Only Act", ArtistStatus.SEED);

        mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", "alice@example.com"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Alice Only Act")))
                .andExpect(content().string(not(containsString("Bob Only Act"))));
    }
}
