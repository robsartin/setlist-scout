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
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real per-item / per-group candidate review actions (approve/reject) against a booted
 * context + Postgres, signed in as a test user, and checks owner isolation + the no-JS fallback.
 * Runs in CI (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CandidateActionsTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    private static final String TOM_PETTY = "Tom Petty and the Heartbreakers";
    private static final String WILCO = "Wilco";

    private Long savePending(String owner, String name, ArtistSource source, String discoveredVia) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, "note for " + name);
        artist.setOwner(owner);
        return artistRepository.save(artist).getId();
    }

    @Test
    void approveChangesOnlyThatArtistToApproved() throws Exception {
        String owner = "actions-approve@example.com";
        Long id = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/{id}/approve", id)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ArtistStatus.APPROVED);
    }

    @Test
    void approveHtmxReturnsBareFragment() throws Exception {
        String owner = "actions-approve-htmx@example.com";
        Long id = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", id)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("<head").doesNotContain("topbar");
        assertThat(artistRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ArtistStatus.APPROVED);
    }

    @Test
    void approveDoesNotTouchAnotherOwnersArtist() throws Exception {
        String owner = "actions-approve-owner-a@example.com";
        String otherOwner = "actions-approve-owner-b@example.com";
        Long othersId = savePending(otherOwner, "Not Yours", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/{id}/approve", othersId)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))));

        assertThat(artistRepository.findById(othersId).orElseThrow().getStatus())
                .isEqualTo(ArtistStatus.PENDING_REVIEW);
    }

    @Test
    void rejectChangesArtistToRejected() throws Exception {
        String owner = "actions-reject@example.com";
        Long id = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/{id}/reject", id)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ArtistStatus.REJECTED);
    }

    @Test
    void rejectHtmxReturnsBareFragment() throws Exception {
        String owner = "actions-reject-htmx@example.com";
        Long id = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/reject", id)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("<head").doesNotContain("topbar");
        assertThat(artistRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ArtistStatus.REJECTED);
    }

    @Test
    void groupRejectChangesOnlyThatGroupsPendingRows() throws Exception {
        String owner = "actions-group-reject@example.com";
        Long member1 = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long member2 = savePending(owner, "Benmont Tench", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long similar = savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);
        Long otherGroup = savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);

        mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", "MEMBER_EXPANSION")
                        .param("decision", "reject")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(member1).orElseThrow().getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(artistRepository.findById(member2).orElseThrow().getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(artistRepository.findById(similar).orElseThrow().getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(artistRepository.findById(otherGroup).orElseThrow().getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
    }

    @Test
    void groupApproveHtmxReturnsBareFragment() throws Exception {
        String owner = "actions-group-approve-htmx@example.com";
        Long member1 = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", "MEMBER_EXPANSION")
                        .param("decision", "approve")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("<head").doesNotContain("topbar");
        assertThat(artistRepository.findById(member1).orElseThrow().getStatus()).isEqualTo(ArtistStatus.APPROVED);
    }

    @Test
    void groupActionOnlyTouchesRequestingOwnersRows() throws Exception {
        String owner = "actions-group-owner-a@example.com";
        String otherOwner = "actions-group-owner-b@example.com";
        Long mine = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long theirs = savePending(otherOwner, "Someone Else", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", "MEMBER_EXPANSION")
                        .param("decision", "approve")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(mine).orElseThrow().getStatus()).isEqualTo(ArtistStatus.APPROVED);
        assertThat(artistRepository.findById(theirs).orElseThrow().getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
    }
}
