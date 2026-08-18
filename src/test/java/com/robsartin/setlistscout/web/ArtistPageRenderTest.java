package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
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
    void approveAllHtmxReturnsBareCandidatesApp() throws Exception {
        String owner = "render-approve-all@example.com";
        savePending(owner, "Some Pending Act", ArtistSource.SIMILAR_EXPANSION, "Dawes", "similar to Dawes");

        String res = mockMvc.perform(post("/artists/approve-all-pending").header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(res).contains("candidates-app");
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

    @Test
    void seedRowShowsRemoveFromSeedListButton() throws Exception {
        String owner = "render-seed-button@example.com";
        saveActive(owner, "Seed Artist", ArtistStatus.SEED);

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Seed Artist");
        assertThat(body).contains("Remove from seed list");
        assertThat(body).contains("/remove-from-seed");
        // Must NOT also render the plain Remove button (APPROVED-only, sets REJECTED) --
        // its th:action is the "/remove" URL, not the button label (whose text overlaps
        // "Remove from seed list").
        assertThat(body).doesNotContain("/remove\"");
    }

    @Test
    void approvedRowShowsRemoveButton() throws Exception {
        String owner = "render-approved-button@example.com";
        saveActive(owner, "Approved Artist", ArtistStatus.APPROVED);

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Approved Artist");
        assertThat(body).contains("Remove Approved Artist");
        assertThat(body).contains("/artists");
        assertThat(body).contains("/remove\"");
        // Must NOT also render the SEED-only "Remove from seed list" button.
        assertThat(body).doesNotContain("Remove from seed list");
    }

    @Test
    @DisplayName("issue #175: the add-artist form and the upload controls render ABOVE the active "
            + "list -- the table can run to 1,500+ rows, so the most frequent action on the page "
            + "must not sit below the whole thing")
    void addAndUploadControlsRenderAboveTheActiveList() throws Exception {
        String owner = "render-forms-above-list@example.com";
        saveActive(owner, "Existing Artist", ArtistStatus.SEED);

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int activeSectionIndex = body.indexOf("id=\"active-section\"");
        int addFormIndex = body.indexOf("action=\"/artists/seed\"");
        int uploadFormIndex = body.indexOf("action=\"/artists/upload\"");

        assertThat(activeSectionIndex).isPositive();
        assertThat(addFormIndex).as("add-artist form must render, and above the active list").isPositive();
        assertThat(uploadFormIndex).as("upload form must render, and above the active list").isPositive();
        assertThat(addFormIndex).isLessThan(activeSectionIndex);
        assertThat(uploadFormIndex).isLessThan(activeSectionIndex);
    }

    @Test
    @DisplayName("issue #175: the add-artist text input keeps a proper label after the move -- "
            + "this app treats labelled controls as an acceptance criterion, not a nicety")
    void addArtistInputHasAProperLabel() throws Exception {
        String owner = "render-add-artist-label@example.com";

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("<label for=\"artist-name\">Add a band to the seed list</label>");
        assertThat(body).contains("id=\"artist-name\"");
    }

    @Test
    @DisplayName("issue #175: a plain page load claims no autofocus")
    void plainPageLoadHasNoAutofocus() throws Exception {
        String owner = "render-no-autofocus-on-load@example.com";
        saveActive(owner, "Existing Artist", ArtistStatus.SEED);

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countAutofocusElements(body)).isZero();
    }

    @Test
    @DisplayName("issue #175: an htmx add claims no autofocus either -- the add-artist input sits "
            + "outside #active-section (the only thing the response swaps), so htmx never touches "
            + "it and focus stays on it by itself, with nothing left to claim")
    void htmxAddHasNoAutofocus() throws Exception {
        String owner = "render-add-focus@example.com";

        String body = mockMvc.perform(post("/artists/seed").param("name", "Wilco")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countAutofocusElements(body)).isZero();
        // The response is only ever the table fragment -- the add form (and its input) isn't
        // part of it, which is WHY there's nothing here to steal focus away from that input.
        assertThat(body).doesNotContain("id=\"artist-name\"");
    }

    // --- Issue #174: keyset pagination ------------------------------------------------------------

    private void saveSequence(String owner, String prefix, int count) {
        for (int i = 1; i <= count; i++) {
            saveActive(owner, String.format("%s %02d", prefix, i), ArtistStatus.SEED);
        }
    }

    @Test
    @DisplayName("issue #174: the first page has a Next link and no Previous; real, labelled links "
            + "(not glyphs), a real href for the non-JS fallback, and the current position is "
            + "conveyed as visible text, not only implied by the table")
    void firstPageHasNextButNoPreviousWithAccessibleControls() throws Exception {
        String owner = "render-pagination-first-page@example.com";
        saveSequence(owner, "Band", 25);

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Band 01").contains("Band 20");
        assertThat(body).as("21st-25th rows belong to page 2").doesNotContain("Band 21");
        assertThat(body).as("real labelled link, not a bare glyph").contains(">Next page<");
        assertThat(body).as("real href -- works with the non-JS fallback").contains("href=\"/artists?after=");
        assertThat(body).contains("id=\"active-next\"");
        assertThat(body).as("first page has no previous").doesNotContain("Previous page")
                .doesNotContain("id=\"active-prev\"");
        assertThat(body).as("no bare glyphs anywhere in the controls").doesNotContain("«").doesNotContain("»");
        assertThat(body).as("current position conveyed as visible text")
                .contains("id=\"active-position\"").contains("Showing 20 artists, Band 01 to Band 20.");
    }

    @Test
    @DisplayName("issue #174: the last (partial) page has a Previous link and no Next")
    void lastPageHasPreviousButNoNext() throws Exception {
        String owner = "render-pagination-last-page@example.com";
        saveSequence(owner, "Band", 25);
        String cursor = ArtistNameNormalizer.normalize("Band 20");

        String body = mockMvc.perform(get("/artists").param("after", cursor)
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Band 21").contains("Band 25");
        assertThat(body).as("Band 20 belongs to page 1").doesNotContain("Band 20");
        assertThat(body).as("real labelled link, not a bare glyph").contains(">Previous page<");
        assertThat(body).contains("id=\"active-prev\"");
        assertThat(body).as("nothing left past the 25th").doesNotContain("Next page")
                .doesNotContain("id=\"active-next\"");
        assertThat(body).contains("Showing 5 artists, Band 21 to Band 25.");
    }

    @Test
    @DisplayName("issue #174: Previous, following Next, returns exactly the first page again -- via "
            + "a plain (non-htmx) GET, proving the non-JS fallback th:href actually works, not just "
            + "the htmx-enhanced th:hx-get")
    void previousAfterNextReturnsExactlyTheFirstPageViaPlainGet() throws Exception {
        String owner = "render-pagination-previous@example.com";
        saveSequence(owner, "Band", 25);

        // Plain GET, no HX-Request header at all.
        String page2 = mockMvc.perform(get("/artists").param("after", ArtistNameNormalizer.normalize("Band 20"))
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(page2).as("plain GET still renders the full page, topbar included")
                .contains("id=\"active-section\"").contains(">Shows<");
        assertThat(page2).contains("Band 21");

        String page1Again = mockMvc.perform(get("/artists").param("before", ArtistNameNormalizer.normalize("Band 21"))
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        for (int i = 1; i <= 20; i++) {
            assertThat(page1Again).contains(String.format("Band %02d", i));
        }
        assertThat(page1Again).as("exactly the first page -- 21st row not included").doesNotContain("Band 21");
        assertThat(page1Again).as("back on page 1: no previous, but a next").doesNotContain("Previous page")
                .contains(">Next page<");
    }

    @Test
    @DisplayName("issue #174: mixed-case names render in normalized-alphabetical order, not raw "
            + "case-sensitive byte order (which would put every capitalized name before any "
            + "lowercase one)")
    void mixedCaseNamesRenderInNormalizedOrder() throws Exception {
        String owner = "render-pagination-mixed-case@example.com";
        saveActive(owner, "zebra", ArtistStatus.SEED);
        saveActive(owner, "Cherry", ArtistStatus.SEED);
        saveActive(owner, "APPLE", ArtistStatus.SEED);
        saveActive(owner, "banana", ArtistStatus.SEED);

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int apple = body.indexOf(">APPLE<");
        int banana = body.indexOf(">banana<");
        int cherry = body.indexOf(">Cherry<");
        int zebra = body.indexOf(">zebra<");
        assertThat(apple).as("APPLE renders at all").isPositive();
        assertThat(apple).isLessThan(banana);
        assertThat(banana).isLessThan(cherry);
        assertThat(cherry).isLessThan(zebra);
    }

    @Test
    @DisplayName("issue #174: an htmx page change (Next/Previous) announces the new position via "
            + "the shared #sr-status region with the load-bearing hx-swap-oob=\"innerHTML\" swap "
            + "style (CLAUDE.md: the default hx-swap-oob=\"true\" would strip role/aria-live and "
            + "silently kill every announcement after the first); a plain page load carries no such "
            + "OOB duplicate of the layout's one real #sr-status node")
    void htmxPageChangeAnnouncesViaSrStatusOutOfBandButPlainLoadDoesNot() throws Exception {
        String owner = "render-pagination-announce@example.com";
        saveSequence(owner, "Band", 25);
        String cursor = ArtistNameNormalizer.normalize("Band 20");

        String htmxBody = mockMvc.perform(get("/artists").param("after", cursor)
                        .header("HX-Request", "true")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(htmxBody).containsPattern(
                "id=\"sr-status\"[^>]*hx-swap-oob=\"innerHTML\"|hx-swap-oob=\"innerHTML\"[^>]*id=\"sr-status\"");
        assertThat(htmxBody).contains("Showing 5 artists, Band 21 to Band 25.");

        String plainBody = mockMvc.perform(get("/artists").param("after", cursor)
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(plainBody).as("no OOB duplicate of the layout's one real #sr-status node")
                .doesNotContain("hx-swap-oob=\"innerHTML\"");
        assertThat(countOccurrences(plainBody, "id=\"sr-status\"")).as("exactly one #sr-status node").isEqualTo(1);
    }

    private static int countOccurrences(String body, String needle) {
        int count = 0;
        int index = 0;
        while ((index = body.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    @Test
    @DisplayName("issue #174: after adding an artist, the response lands on the FIRST page -- the "
            + "newly-added artist (sorted first here) appears, and the row it pushed onto page 2 "
            + "does not. This is the after-add landing decision the issue asks to be tested.")
    void addingAnArtistLandsOnTheFirstPage() throws Exception {
        String owner = "render-pagination-after-add@example.com";
        saveSequence(owner, "Band", 25);

        String body = mockMvc.perform(post("/artists/seed").param("name", "AAA New Arrival")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).as("the newly-added artist is visible on the landing page").contains("AAA New Arrival");
        for (int i = 1; i <= 19; i++) {
            assertThat(body).contains(String.format("Band %02d", i));
        }
        assertThat(body).as("26 rows now exist; Band 20 is the 21st and was pushed to page 2")
                .doesNotContain("Band 20");
        assertThat(body).contains(">Next page<");
        assertThat(body).as("this IS the first page").doesNotContain("Previous page");
    }
}
