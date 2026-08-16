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

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

        assertThat(Pattern.compile("Candidates[\\s\\S]{0,20}aria-current=\"page\"|aria-current=\"page\"[\\s\\S]{0,40}Candidates")
                .matcher(body).find())
                .as("Candidates nav link should carry aria-current=\"page\": %s", body)
                .isTrue();

        // Wilco is biggest (30 > 2) -- every one of its rows renders directly, no pagination.
        assertThat(body).contains(WILCO);
        assertThat(body).contains("Wilco Member 1<");
        assertThat(body).contains("Wilco Member 30<");
        assertThat(body).doesNotContain("Show more");

        // Tom Petty is in the sidebar (name + count), not expanded -- its rows are NOT on the page.
        assertThat(body).contains(TOM_PETTY);
        assertThat(body).doesNotContain("Mike Campbell");
        assertThat(body).doesNotContain("Jackson Browne");

        // Repo-wide, nothing else asserts a rendered URL or htmx attribute. CLAUDE.md documents
        // hx-get="@{/x}" shipping a literal "@{/x}" (a missing th: prefix) as a repeat-offender
        // gotcha, and an instance of exactly that bug class already shipped on this branch once
        // (the broken sidebar nav) without any test catching it -- the sidebar test drives the
        // endpoint directly with a header, so it passes even if the template ships a literal
        // "@{...}". Assert the actual resolved URLs are present, and that no unresolved Thymeleaf
        // expression leaks into the HTML at all.
        assertThat(body).contains("/artists/candidates?via=");
        assertThat(Pattern.compile("/artists/\\d+/approve").matcher(body).find())
                .as("should contain a rendered per-row approve URL: %s", body)
                .isTrue();
        assertThat(Pattern.compile("/artists/\\d+/reject").matcher(body).find())
                .as("should contain a rendered per-row reject URL: %s", body)
                .isTrue();
        assertThat(body).contains("/artists/candidates/group");
        assertThat(body).doesNotContain("@{");

        // #148 fix round 4: hx-disabled-elt="this" targeted the <form>, where `disabled` isn't a
        // valid HTML attribute (browsers silently ignore it, so the buttons never actually
        // disabled). Assert the real fix -- the form's own submit button -- rendered, and that
        // the dead value hasn't regressed back in.
        assertThat(body).contains("hx-disabled-elt=\"find button\"");
        assertThat(body).doesNotContain("hx-disabled-elt=\"this\"");

        // Minor 4 (#148 fix round 3): a skip link past the ~294-entry sidebar to the focused group,
        // for keyboard users -- see app.css's .visually-hidden:focus and the id="current-group"
        // skip target below.
        assertThat(body).contains("href=\"#current-group\"");
        assertThat(body).contains("id=\"current-group\"");
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
    void htmxGetReturnsTheBareFragmentNotTheFullPage() throws Exception {
        String owner = "candidates-htmx-get@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates").param("via", TOM_PETTY)
                        .header("HX-Request", "true")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The sidebar-link navigation (issue #148) does a plain htmx GET back to this same
        // endpoint. Without an HX-Request branch it returns the full page, and htmx's makeFragment
        // then swaps in the parsed <body>'s children -- duplicating the topbar/h1/page-sub inside
        // #candidates-app on every click. The response here must be the bare candidatesApp fragment.
        assertThat(body).contains(TOM_PETTY);
        assertThat(body).contains("Mike Campbell");
        assertThat(body).doesNotContain("<head").doesNotContain("topbar");

        // #155: this swap destroys the previously-focused element exactly like a per-row action
        // does, so it needs a focus target too -- the group anchor, since there's no acted-on row
        // to succeed. Regression this guards: simplifying the controller's `fragment` condition to
        // `hxRequest != null` (dropping the history-restore check) still passes every other test on
        // this path.
        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("id=\"current-group\"[^>]*autofocus");
    }

    @Test
    void historyRestoreRequestReturnsTheFullPageNotTheBareFragment() throws Exception {
        String owner = "candidates-history-restore@example.com";
        seedTwoGroups(owner);

        // On a history-cache MISS (production has ~294 sidebar groups; vendored htmx's
        // historyCacheSize is 10), htmx re-issues the sidebar link's GET with BOTH HX-Request AND
        // HX-History-Restore-Request set, then does swapInnerHTML(<body>, response) -- it needs the
        // FULL page here, unlike a plain HX-Request GET (see htmxGetReturnsTheBareFragmentNotThe
        // FullPage above), or the topbar/nav/h1/page-sub get wiped out until a manual reload.
        String body = mockMvc.perform(get("/artists/candidates").param("via", TOM_PETTY)
                        .header("HX-Request", "true")
                        .header("HX-History-Restore-Request", "true")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("topbar");
        assertThat(body).contains("<head");

        // #155: a history restore is a full page render -- it must NOT carry autofocus, or the
        // browser honours it natively on load and yanks focus into the middle of the list instead
        // of leaving it at the top of the document. Regression this guards: the same simplified
        // `fragment` condition that would break the assertion above would also make this one an
        // (incorrect) ANCHOR-focus fragment response.
        assertThat(countAutofocusElements(body)).isEqualTo(0);
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

    @Test
    void requestingAnotherOwnersRealGroupNameAsViaShowsOwnGroupNotTheirs() throws Exception {
        // Pins the actual security property on the render path: unlike candidatesAreIsolatedByOwner
        // (no via at all) and viaParamForAGroupWithNoPendingRowsFallsBackToBiggest (a via that
        // doesn't exist for ANY owner), this uses a via that's a REAL, currently-pending group --
        // just for a different owner. CandidateGroups.resolve must fall back to this owner's own
        // biggest group, never render the other owner's rows.
        String ownerA = "candidates-cross-owner-a@example.com";
        String ownerB = "candidates-cross-owner-b@example.com";
        seedTwoGroups(ownerA);
        savePending(ownerB, "Bob Only Act", ArtistSource.SIMILAR_EXPANSION, "Dawes");

        String body = mockMvc.perform(get("/artists/candidates").param("via", TOM_PETTY)
                        .with(oidcLogin().idToken(t -> t.claim("email", ownerB))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Dawes");
        assertThat(body).contains("Bob Only Act");
        assertThat(body).doesNotContain(TOM_PETTY);
        assertThat(body).doesNotContain(WILCO);
        assertThat(body).doesNotContain("Mike Campbell");
        assertThat(body).doesNotContain("Jackson Browne");
    }

    @Test
    void bulkClearingOneRelationTypeLeavesTheGroupCurrentWhenAnotherTypeIsStillPending() throws Exception {
        // The spec explicitly says a partial bulk-clear must NOT auto-advance. Existing coverage
        // only had the emptying case (via ReviewControllerTest's mocked htmx assertion) and a
        // DB-status-only non-htmx test -- nothing rendered the SAME group afterward with one
        // relation section gone and another still intact.
        String owner = "candidates-partial-bulk@example.com";
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Benmont Tench", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Jackson Browne", ArtistSource.SIMILAR_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(post("/artists/candidates/group")
                        .param("via", TOM_PETTY)
                        .param("type", "MEMBER_EXPANSION")
                        .param("decision", "reject")
                        .header("HX-Request", "true").with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Still the SAME current group -- Similar survives, Members is gone, no auto-advance.
        assertThat(body).contains(TOM_PETTY);
        assertThat(body).contains("Jackson Browne");
        assertThat(body).doesNotContain("Mike Campbell");
        assertThat(body).doesNotContain("Benmont Tench");
    }

    @Test
    void clearingEveryGroupOneByOneAutoAdvancesThroughAllOfThemToTheRealEmptyState() throws Exception {
        String owner = "candidates-capstone@example.com";
        Long tomPettyRow = artistRepository.save(pendingArtist(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY)).getId();
        Long wilcoRow = artistRepository.save(pendingArtist(owner, "Nels Cline", ArtistSource.MEMBER_EXPANSION, WILCO)).getId();
        Long dawesRow = artistRepository.save(pendingArtist(owner, "Taylor Goldsmith", ArtistSource.MEMBER_EXPANSION, "Dawes")).getId();

        // Land on whatever's biggest (all groups are size 1 here, so any consistent tie-break is fine)
        // -- clear it, then clear whatever's next, then the last, confirming each response carries
        // exactly the remaining groups and the final one is the real empty state.
        String afterFirst = mockMvc.perform(post("/artists/{id}/reject", tomPettyRow)
                        .header("HX-Request", "true").with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterFirst).doesNotContain(TOM_PETTY);
        assertThat(afterFirst).satisfiesAnyOf(
                b -> assertThat(b).contains(WILCO),
                b -> assertThat(b).contains("Dawes"));

        String afterSecond = mockMvc.perform(post("/artists/{id}/reject", wilcoRow)
                        .header("HX-Request", "true").with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andReturn().getResponse().getContentAsString();
        // Minor 9: must actually land ON Dawes here, not merely have dropped the first two -- as
        // written before this fix, auto-advance skipping Dawes entirely and jumping straight to the
        // empty state would have passed this assertion just as well.
        assertThat(afterSecond).doesNotContain(TOM_PETTY).doesNotContain(WILCO).contains("Dawes");

        String afterThird = mockMvc.perform(post("/artists/{id}/reject", dawesRow)
                        .header("HX-Request", "true").with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andReturn().getResponse().getContentAsString();
        assertThat(afterThird).contains("Nothing pending. Run expansion to find more.");
    }

    @Test
    void globalPendingCountReflectsRealityAfterAnAction() throws Exception {
        // Minor 2: the nav badge is outside #candidates-app and never updates on an htmx swap, so
        // the in-page .globalbar .count-label is the only running total that stays live mid-session.
        // Confirm it actually reflects the new total after an action, not the count from page load.
        String owner = "candidates-pending-count@example.com";
        seedTwoGroups(owner); // 30 Wilco + 2 Tom Petty = 32 pending
        Long aWilcoRow = artistRepository.findByOwnerAndStatus(owner, ArtistStatus.PENDING_REVIEW).stream()
                .filter(a -> WILCO.equals(a.getDiscoveredVia()))
                .findFirst().orElseThrow().getId();

        String body = mockMvc.perform(post("/artists/{id}/reject", aWilcoRow)
                        .header("HX-Request", "true").with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("31 pending total");
    }

    @Test
    void candidateRowsRenderAlphabeticallyWithinTheirRelationGroup() throws Exception {
        String owner = "candidates-row-order@example.com";
        // Saved deliberately out of order: insertion order is Zeta, Alpha, Mike.
        savePending(owner, "Zeta Reticuli", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Alpha Centauri", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);
        savePending(owner, "Mike Campbell", ArtistSource.MEMBER_EXPANSION, TOM_PETTY);

        String body = mockMvc.perform(get("/artists/candidates").param("via", TOM_PETTY)
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body.indexOf("Alpha Centauri"))
                .as("rows render A-Z, not in insertion order")
                .isLessThan(body.indexOf("Mike Campbell"));
        assertThat(body.indexOf("Mike Campbell")).isLessThan(body.indexOf("Zeta Reticuli"));
    }

    @Test
    void aFullPageLoadNeverStealsFocus() throws Exception {
        String owner = "candidates-no-autofocus@example.com";
        seedTwoGroups(owner);

        String body = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // autofocus is for htmx swaps only -- on a normal page load the browser would honour it
        // natively and yank the user into the middle of the list.
        assertThat(body).doesNotContain("autofocus");
    }

    private Artist pendingArtist(String owner, String name, ArtistSource source, String discoveredVia) {
        Artist artist = new Artist(name, source, ArtistStatus.PENDING_REVIEW, discoveredVia, "note for " + name);
        artist.setOwner(owner);
        return artist;
    }
}
