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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real /artists/candidates focused-single-group page (issue #148) against a booted
 * context + Postgres, signed in as a test user, and checks multi-tenant isolation. Each test uses
 * a distinct owner so saved data can't leak between methods (no per-test rollback). Runs in CI
 * (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CandidatesPageRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    private static final String TOM_PETTY = "Tom Petty and the Heartbreakers";
    private static final String WILCO = "Wilco";

    private void savePending(String owner, String name, ArtistSource source, String discoveredVia) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, "note for " + name);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    /** Wilco (30 rows, the biggest group) and Tom Petty (2 rows, Members + Similar) for the owner. */
    private void seedTwoGroups(String owner) {
        for (int i = 1; i <= 30; i++) {
            savePending(owner, "Wilco Member " + i, ArtistSource.MEMBER_EXPANSION, WILCO);
        }
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);
    }

    @Test
    void landingWithNoViaShowsTheBiggestGroupInFullAndSidebarsTheRest() throws Exception {
        String owner = "candidates-land-biggest@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("/css/app.css");
        assertThat(body).contains(">Shows<");

        // Wilco is biggest (30 > 2) -- every one of its rows renders directly, no pagination.
        assertThat(body).contains(WILCO);
        assertThat(body).contains("Wilco Member 1<");
        assertThat(body).contains("Wilco Member 30<");
        assertThat(body).doesNotContain("Show more");

        // Tom Petty is in the sidebar (name + count), not expanded -- its rows are NOT on the page.
        assertThat(body).contains(TOM_PETTY);
        assertThat(body).doesNotContain("Mike Campbell");
        assertThat(body).doesNotContain("Jackson Browne");
    }

    @Test
    void viaParamShowsThatSpecificGroupRegardlessOfSize() throws Exception {
        String owner = "candidates-via-param@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates").param("via", TOM_PETTY)
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Mike Campbell");
        assertThat(body).contains("Jackson Browne");
        assertThat(body).doesNotContain("Wilco Member 1<");

        // Wilco is now in the sidebar instead.
        assertThat(body).contains(WILCO);
    }

    @Test
    void viaParamForAGroupWithNoPendingRowsFallsBackToBiggest() throws Exception {
        String owner = "candidates-via-stale@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates").param("via", "Not A Real Group")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Wilco Member 1<");
    }

    @Test
    void noPendingCandidatesShowsEmptyStateNotABrokenGroup() throws Exception {
        String owner = "candidates-empty@example.com";

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Nothing pending. Run expansion to find more.");
    }

    @Test
    void candidatesAreIsolatedByOwner() throws Exception {
        seedTwoGroups("candidates-owner-a@example.com");
        savePending("candidates-owner-b@example.com", "Bob Only Act", ArtistSource.SIMILAR_EXPANSION, "Dawes");

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", "candidates-owner-b@example.com"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Dawes");
        assertThat(body).doesNotContain(TOM_PETTY);
        assertThat(body).doesNotContain(WILCO);
    }
}
