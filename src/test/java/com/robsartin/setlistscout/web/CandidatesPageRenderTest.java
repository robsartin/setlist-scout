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

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real /artists/candidates Thymeleaf templates (grouped headers + lazy rows fragment)
 * against a booted context + Postgres, signed in as a test user, and checks multi-tenant isolation.
 * Each test uses a distinct owner so saved data can't leak between methods (no per-test rollback).
 * Runs in CI (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CandidatesPageRenderTest {

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

    private static final String TOM_PETTY = "Tom Petty and the Heartbreakers";
    private static final String WILCO = "Wilco";

    private void savePending(String owner, String name, ArtistSource source, String discoveredVia) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, "note for " + name);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    /** Seeds 2 base artists x relation types for the given owner, including a 26-row group (show-more). */
    private void seedGroupedCandidates(String owner) {
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Benmont Tench", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);
        for (int i = 1; i <= 26; i++) {
            savePending(owner, "Wilco Member " + i, ArtistSource.MEMBER_EXPANSION, WILCO);
        }
    }

    @Test
    void candidatesPageShowsGroupHeadersAndCountsButNotRows() throws Exception {
        String owner = "render-candidates-headers@example.com";
        seedGroupedCandidates(owner);

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("/css/app.css");
        assertThat(body).contains(">Shows<");
        assertThat(body).contains(TOM_PETTY);
        assertThat(body).contains(WILCO);
        assertThat(body).contains("<details");

        // aria-current="page" lands on the Candidates nav link specifically.
        assertThat(Pattern.compile("Candidates[\\s\\S]{0,20}aria-current=\"page\"|aria-current=\"page\"[\\s\\S]{0,40}Candidates")
                .matcher(body).find())
                .as("Candidates nav link should carry aria-current=\"page\": %s", body)
                .isTrue();

        // Relation-group chip + count: "Members" section shows 2, "Similar" section shows 1.
        assertThat(Pattern.compile("Members[\\s\\S]{0,60}?>2<").matcher(body).find())
                .as("Members group should show count 2: %s", body)
                .isTrue();
        assertThat(Pattern.compile("Similar[\\s\\S]{0,60}?>1<").matcher(body).find())
                .as("Similar group should show count 1: %s", body)
                .isTrue();

        // Rows are lazy -- the initial page must not contain individual candidate names.
        assertThat(body).doesNotContain("Mike Campbell");
        assertThat(body).doesNotContain("Benmont Tench");
        assertThat(body).doesNotContain("Jackson Browne");
    }

    @Test
    void candidateRowsEndpointReturnsBareFragmentWithMemberNamesAndControls() throws Exception {
        String owner = "render-candidates-rows@example.com";
        seedGroupedCandidates(owner);

        String body = mockMvc.perform(get("/artists/candidates/rows")
                        .param("via", TOM_PETTY)
                        .param("type", "MEMBER_EXPANSION")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Mike Campbell");
        assertThat(body).contains("Benmont Tench");
        assertThat(body).doesNotContain("Jackson Browne"); // that's the Similar group, not Members

        // Bare fragment: no page shell.
        assertThat(body).doesNotContain("<head");
        assertThat(body).doesNotContain("topbar");

        // Per-item approve/reject controls pointing at the (Task-4) per-artist routes.
        assertThat(Pattern.compile("/artists/\\d+/approve").matcher(body).find())
                .as("expected an approve control targeting /artists/{id}/approve: %s", body)
                .isTrue();
        assertThat(Pattern.compile("/artists/\\d+/reject").matcher(body).find())
                .as("expected a reject control targeting /artists/{id}/reject: %s", body)
                .isTrue();

        // Only 2 rows in this group -- no "Show more".
        assertThat(body).doesNotContain("Show more");
    }

    @Test
    void groupLargerThanPageLimitYieldsShowMoreWithNextOffset() throws Exception {
        String owner = "render-candidates-showmore@example.com";
        seedGroupedCandidates(owner);

        String body = mockMvc.perform(get("/artists/candidates/rows")
                        .param("via", WILCO)
                        .param("type", "MEMBER_EXPANSION")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Show more");
        assertThat(body).contains("offset=25");
    }

    @Test
    void candidatesAreIsolatedByOwner() throws Exception {
        seedGroupedCandidates("candidates-owner-a@example.com");
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
