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
 * Drives the real per-item candidate review actions (approve/reject) against a booted context +
 * Postgres, signed in as a test user: status changes, owner isolation, and the auto-advance
 * response shape (issue #148). Runs in CI (needs Docker).
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

    // application.yml's defaults, which nothing in this suite overrides (src/test/resources/
    // application.properties only turns the pollers off): this address is the admin AND the whole
    // allow-list, so NavModelAdvice's otherOwnerEmails is empty for every test in this class.
    private static final String ADMIN_EMAIL = "rob.sartin@gmail.com";

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
    void approvingTheLastRowInAGroupsOnlySectionAutoAdvancesToTheNextGroup() throws Exception {
        String owner = "actions-auto-advance@example.com";
        // Wilco is bigger, so it's the initial "current" group; Tom Petty has exactly one row.
        for (int i = 1; i <= 3; i++) {
            savePending(owner, "Wilco Member " + i, ArtistSource.MEMBER_EXPANSION, WILCO);
        }
        Long lastTomPettyRow = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        // Land on Tom Petty specifically (it's not the biggest, so this proves `via` navigation first).
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/artists/candidates").param("via", TOM_PETTY)
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk());

        String body = mockMvc.perform(post("/artists/{id}/approve", lastTomPettyRow)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Tom Petty had only that one row -- clearing it empties the group, so the response is the
        // NEXT group (Wilco, the only one left) shown in full, not a bare empty swap.
        assertThat(body).contains(WILCO);
        assertThat(body).contains("Wilco Member 1<");
        assertThat(body).doesNotContain(TOM_PETTY);
        assertThat(body).doesNotContain("<head").doesNotContain("topbar");
    }

    @Test
    void approvingOneRowWhenOthersRemainInTheSameSectionStaysOnTheCurrentGroup() throws Exception {
        String owner = "actions-stay-put@example.com";
        Long member1 = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Benmont Tench", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", member1)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Benmont Tench is still pending in the same group -- current group unchanged, just refreshed.
        assertThat(body).contains(TOM_PETTY);
        assertThat(body).contains("Benmont Tench");
        // #155: "Mike Campbell" itself now legitimately appears once more, inside the OOB
        // announcement ("Approved Mike Campbell."). Anchor on "<" (the candidate row's rendered
        // form is "...cand-name\">Mike Campbell</span>...") so this still catches a real
        // regression -- the row itself reappearing -- without tripping on the new announcement
        // text, which always ends the name in "." rather than "<".
        assertThat(body).doesNotContain("Mike Campbell<");
    }

    @Test
    void clearingTheLastGroupShowsTheRealEmptyState() throws Exception {
        String owner = "actions-clear-last@example.com";
        Long onlyRow = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/reject", onlyRow)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Nothing left to review. Run expansion to find more.");
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

    @Test
    void groupBulkRejectThatEmptiesTheWholeGroupAutoAdvances() throws Exception {
        String owner = "actions-group-bulk-advance@example.com";
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY); // Tom Petty's only row
        savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);
        savePending(owner, "Wilco Member 2", ArtistSource.MEMBER_EXPANSION, WILCO);

        String body = mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", "MEMBER_EXPANSION")
                        .param("decision", "reject")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(WILCO);
        // #155: the OOB announcement now legitimately names the just-cleared group once
        // ("Rejected 1 Members from Tom Petty and the Heartbreakers."). Anchor on "<" -- every
        // rendered-group form (sidebar entry, current-group-title) puts the via name directly
        // before a closing tag, while the announcement always puts it before "." -- so this still
        // catches Tom Petty reappearing as an actual group, without tripping on the new sentence.
        assertThat(body).doesNotContain(TOM_PETTY + "<");
    }

    @Test
    void globalApproveAllThatEmptiesEverythingShowsRealEmptyState() throws Exception {
        String owner = "actions-global-approve-all@example.com";
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);

        String body = mockMvc.perform(post("/artists/approve-all-pending")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Nothing left to review. Run expansion to find more.");
    }

    @Test
    void globalRejectAllOnlyTouchesThisOwnersRows() throws Exception {
        String owner = "actions-global-reject-owner-a@example.com";
        String otherOwner = "actions-global-reject-owner-b@example.com";
        Long mine = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long theirs = savePending(otherOwner, "Someone Else", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        mockMvc.perform(post("/artists/reject-all-pending")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(artistRepository.findById(mine).orElseThrow().getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(artistRepository.findById(theirs).orElseThrow().getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
    }

    @Test
    void expandNowKeepsTheCurrentGroupInView() throws Exception {
        String owner = "actions-expand-now@example.com";
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);

        // Land on Tom Petty specifically, then run expansion -- nothing about pending rows changed
        // (expand-now only re-dues background jobs), so the same group should still be current.
        String body = mockMvc.perform(post("/artists/expand-now")
                        .param("via", TOM_PETTY)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains(TOM_PETTY);
        assertThat(body).contains("Mike Campbell");
    }

    @Test
    void approvingARowFocusesTheNextRowsApproveButton() throws Exception {
        String owner = "actions-focus-next@example.com";
        Long alpha = savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Charlie Watts", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", alpha)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Exactly one autofocus, and it is on Bravo's Approve button -- the row that took Alpha's place.
        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(autofocusedButtonLabel(body)).isEqualTo("Approve Bravo Company");
    }

    @Test
    void rejectingARowFocusesTheNextRowsRejectButton() throws Exception {
        String owner = "actions-focus-next-reject@example.com";
        Long alpha = savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/reject", alpha)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(autofocusedButtonLabel(body)).isEqualTo("Reject Bravo Company");
    }

    @Test
    void decidingTheLastRowOfARelationGroupFocusesTheGroupAnchor() throws Exception {
        String owner = "actions-focus-anchor@example.com";
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        Long bravo = savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", bravo)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Bravo was last in the list, so there is no successor: focus goes to the group anchor.
        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("id=\"current-group\"[^>]*autofocus");
    }

    @Test
    void autoAdvancingToTheNextGroupFocusesItsAnchor() throws Exception {
        String owner = "actions-advance-focus@example.com";
        for (int i = 1; i <= 3; i++) {
            savePending(owner, "Wilco Member " + i, ArtistSource.MEMBER_EXPANSION, WILCO);
        }
        Long lastTomPettyRow = savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", lastTomPettyRow)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Tom Petty had one row; clearing it advances to Wilco. Focus lands on the new group's
        // anchor -- never on a row of a group the user hasn't seen yet.
        assertThat(body).contains(WILCO);
        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("id=\"current-group\"[^>]*autofocus");
    }

    @Test
    void aRelationTypeBulkActionFocusesTheGroupAnchor() throws Exception {
        String owner = "actions-bulk-anchor@example.com";
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", ArtistSource.MEMBER_EXPANSION.name())
                        .param("decision", "approve")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("id=\"current-group\"[^>]*autofocus");
    }

    @Test
    void clearingEverythingFocusesTheEmptyState() throws Exception {
        String owner = "actions-empty-focus@example.com";
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Wilco Member 1", ArtistSource.MEMBER_EXPANSION, WILCO);

        String body = mockMvc.perform(post("/artists/reject-all-pending")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // No group left, so the anchor doesn't render -- the empty-state paragraph takes the focus.
        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("<p[^>]*tabindex=\"-1\"[^>]*autofocus|<p[^>]*autofocus[^>]*tabindex=\"-1\"");
        assertThat(body).contains("Nothing left to review. Run expansion to find more.");
    }

    @Test
    void sidebarNavigationFocusesTheNewGroupsAnchor() throws Exception {
        String owner = "actions-sidebar-focus@example.com";
        for (int i = 1; i <= 3; i++) {
            savePending(owner, "Wilco Member " + i, ArtistSource.MEMBER_EXPANSION, WILCO);
        }
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/artists/candidates").param("via", TOM_PETTY)
                        .header("HX-Request", "true")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("id=\"current-group\"[^>]*autofocus");
    }

    @Test
    void expandNowFocusesTheSwappedInCopyOfItsOwnTriggerButton() throws Exception {
        String owner = "actions-expand-focus@example.com";
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/expand-now")
                        .param("via", TOM_PETTY)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The trigger cannot hold its own focus across the swap, and relying on htmx's built-in
        // id-based restore does NOT work here (measured in a browser against the vendored 2.0.3
        // build): hx-disabled-elt="find button" sets `disabled` on this button when the request
        // starts, which BLURS it, so document.activeElement is already <body> when the swap runs --
        // and the id-restore is gated on the focused element having LEFT the document, which <body>
        // never does. So the server marks the swapped-in copy of the button autofocus instead; the
        // settle-task focus([autofocus]) runs on it after it is re-inserted and re-enabled.
        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("id=\"expand-now\"[^>]*autofocus|autofocus[^>]*id=\"expand-now\"");
    }

    @Test
    void adminExpandNowFallsBackToTheAnchorWhenItsOwnTriggerIsNotOnThePage() throws Exception {
        // The admin form is gated (candidates.html) on there being another allow-listed owner to
        // target, and this suite runs on application.yml's defaults, where the allow-list is the
        // admin alone -- so the button a TRIGGER outcome would name is not rendered at all. The
        // response must still carry exactly one autofocus (here the empty state, since the admin's
        // own queue is empty) rather than dangle one at an element that isn't there.
        String body = mockMvc.perform(post("/artists/admin/expand-now")
                        .param("targetOwner", "someone-else@example.com")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain("id=\"admin-expand-now\"");
        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).contains("Expansion requested for someone-else@example.com.");
    }

    @Test
    void anActionAnnouncesWhatHappenedOutOfBand() throws Exception {
        String owner = "actions-announce@example.com";
        Long alpha = savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/approve", alpha)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // innerHTML, not the default outerHTML: the live-region NODE has to survive, or a screen
        // reader treats each swap as a brand-new region and announces nothing.
        assertThat(body).containsPattern("id=\"sr-status\"[^>]*hx-swap-oob=\"innerHTML\"|hx-swap-oob=\"innerHTML\"[^>]*id=\"sr-status\"");
        assertThat(body).contains("Approved Alpha Centauri.");
        assertThat(body).contains("1 left in " + TOM_PETTY);
    }

    @Test
    void clearingTheQueueAnnouncesThatNothingIsLeft() throws Exception {
        String owner = "actions-announce-empty@example.com";
        Long only = savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/{id}/reject", only)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Rejected Alpha Centauri.");
        // The announcement's "where you are now" half, matched as its own rendered <span>: the
        // visible empty state now uses the SAME sentence (deliberately -- one state, one phrasing),
        // so a bare contains("Nothing left to review.") would pass on the paragraph alone even if
        // the announcement had gone missing.
        assertThat(body).contains("<span> Nothing left to review.</span>");
    }

    @Test
    void aRelationTypeBulkActionAnnouncesTheCountAndRelationType() throws Exception {
        String owner = "actions-announce-bulk@example.com";
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Bravo Company", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", ArtistSource.MEMBER_EXPANSION.name())
                        .param("decision", "reject")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Rejected 2 Members from " + TOM_PETTY + ".");
    }

    /**
     * The aria-label of the single button carrying autofocus. Both attributes live on the same
     * <button> tag, in either order, so the match is anchored on the tag rather than on ordering.
     */
    private static String autofocusedButtonLabel(String body) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("<button[^>]*autofocus[^>]*aria-label=\"([^\"]+)\"|<button[^>]*aria-label=\"([^\"]+)\"[^>]*autofocus")
                .matcher(body);
        assertThat(m.find()).as("a <button> carrying autofocus").isTrue();
        return m.group(1) != null ? m.group(1) : m.group(2);
    }
}
