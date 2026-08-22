package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.ShowRepository;
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

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real Shows-page hide/unhide actions (issue #166) against a booted context + Postgres,
 * signed in as a test user: the default list excludes hidden shows, the "show hidden" toggle
 * reveals them with an Unhide action, hide/unhide round-trip, owner isolation, and the #155-style
 * focus/announcement contract (autofocus + the shared #sr-status OOB live region). Runs in CI
 * (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ShowHideActionsTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShowRepository showRepository;

    private static Long saveShow(ShowRepository repo, String owner, String artistName,
                                  LocalDateTime eventDateTime, String venueName) {
        return saveShow(repo, owner, artistName, eventDateTime, venueName, Show.Kind.MUSIC);
    }

    private static Long saveShow(ShowRepository repo, String owner, String artistName,
                                  LocalDateTime eventDateTime, String venueName, Show.Kind kind) {
        Show show = new Show(artistName, eventDateTime, venueName, "Austin", null, "ticketmaster", null, kind);
        show.setOwner(owner);
        return repo.save(show).getId();
    }

    /** Same shape, explicit source (issue #220's venue-filter fixtures) -- never hidden by construction. */
    private static Long saveShow(ShowRepository repo, String owner, String artistName,
                                  LocalDateTime eventDateTime, String venueName, String source) {
        Show show = new Show(artistName, eventDateTime, venueName, "Austin", null, source, null, Show.Kind.MUSIC);
        show.setOwner(owner);
        return repo.save(show).getId();
    }

    @Test
    void hidingAShowRemovesItFromTheDefaultListButItReappearsWithTheToggleOn() throws Exception {
        String owner = "hide-basic@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long keep = saveShow(showRepository, owner, "Wilco", when.plusDays(1), "ACL Live");
        Long hideMe = saveShow(showRepository, owner, "Radiohead", when, "Moody Center");

        String afterHide = mockMvc.perform(post("/shows/{id}/hide", hideMe)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(afterHide).contains("Wilco");
        // Not a bare doesNotContain("Radiohead"): the #155-style OOB announcement in this SAME
        // response legitimately says "Hid Radiohead at Moody Center..." (see
        // hidingAnnouncesViaSrStatusAndFocusesExactlyOneElement below). "Radiohead<" anchors on the
        // table cell's actual rendering (`<span>Radiohead</span>`), which the announcement text
        // never produces (mirrors CandidateActionsTest's identical "name<" vs "name." distinction).
        assertThat(afterHide).doesNotContain("Radiohead<");
        assertThat(showRepository.findById(hideMe).orElseThrow().getHiddenAt()).isNotNull();
        assertThat(showRepository.findById(keep).orElseThrow().getHiddenAt()).isNull();

        String withToggle = mockMvc.perform(get("/").param("showHidden", "true")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(withToggle).contains("Wilco");
        assertThat(withToggle).contains("Radiohead");
        assertThat(withToggle).containsPattern("aria-label=\"Unhide Radiohead");

        String withoutToggle = mockMvc.perform(get("/")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(withoutToggle).doesNotContain("Radiohead");
    }

    @Test
    void hideThenUnhideRoundTrips() throws Exception {
        String owner = "hide-roundtrip@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long id = saveShow(showRepository, owner, "Radiohead", when, "Moody Center");

        mockMvc.perform(post("/shows/{id}/hide", id)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk());
        assertThat(showRepository.findById(id).orElseThrow().getHiddenAt()).isNotNull();

        String body = mockMvc.perform(post("/shows/{id}/unhide", id)
                        .param("showHidden", "true")
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(showRepository.findById(id).orElseThrow().getHiddenAt())
                .as("unhide clears hidden_at").isNull();
        assertThat(body).contains("Radiohead");
        assertThat(body).containsPattern("aria-label=\"Hide Radiohead");
    }

    @Test
    void hideDoesNotTouchAnotherOwnersShow() throws Exception {
        String owner = "hide-isolation-a@example.com";
        String otherOwner = "hide-isolation-b@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long theirs = saveShow(showRepository, otherOwner, "Not Yours", when, "Their Venue");

        mockMvc.perform(post("/shows/{id}/hide", theirs)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(showRepository.findById(theirs).orElseThrow().getHiddenAt())
                .as("a foreign show id is a no-op, not a leak").isNull();
    }

    @Test
    void unhideDoesNotTouchAnotherOwnersShow() throws Exception {
        String owner = "unhide-isolation-a@example.com";
        String otherOwner = "unhide-isolation-b@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long theirs = saveShow(showRepository, otherOwner, "Not Yours", when, "Their Venue");
        Show theirShow = showRepository.findById(theirs).orElseThrow();
        theirShow.setHiddenAt(java.time.Instant.now());
        showRepository.save(theirShow);

        mockMvc.perform(post("/shows/{id}/unhide", theirs)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(showRepository.findById(theirs).orElseThrow().getHiddenAt())
                .as("a foreign show id is a no-op -- their hidden show stays hidden").isNotNull();
    }

    @Test
    void hidingAnnouncesViaSrStatusAndFocusesExactlyOneElement() throws Exception {
        String owner = "hide-announce@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long id = saveShow(showRepository, owner, "Radiohead", when, "Moody Center");

        String body = mockMvc.perform(post("/shows/{id}/hide", id)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).containsPattern(
                "id=\"sr-status\"[^>]*hx-swap-oob=\"innerHTML\"|hx-swap-oob=\"innerHTML\"[^>]*id=\"sr-status\"");
        assertThat(body).contains("Hid Radiohead at Moody Center");
        assertThat(countAutofocusElements(body)).isEqualTo(1);
    }

    @Test
    void hidingTheLastVisibleShowFocusesTheRegionAnchor() throws Exception {
        String owner = "hide-last-anchor@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long onlyShow = saveShow(showRepository, owner, "Radiohead", when, "Moody Center");

        String body = mockMvc.perform(post("/shows/{id}/hide", onlyShow)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("id=\"shows-region\"[^>]*autofocus|autofocus[^>]*id=\"shows-region\"");
    }

    @Test
    void hidingARowWithASuccessorFocusesTheNextRowsButton() throws Exception {
        String owner = "hide-successor@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long first = saveShow(showRepository, owner, "Radiohead", when, "Moody Center");
        saveShow(showRepository, owner, "Wilco", when.plusDays(1), "ACL Live");

        String body = mockMvc.perform(post("/shows/{id}/hide", first)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("aria-label=\"Hide Wilco[^\"]*\"[^>]*autofocus");
    }

    /**
     * Issue #220 Finding 2: {@code resolveVisibleShows} must apply the same venue-follow filter as
     * the display list, so it never offers a filtered-out row as the focus successor. Without the
     * fix, {@code focusable} still catches the bad pick and downgrades to the region anchor (no
     * keyboard trap -- see the issue) -- so this test's failure mode BEFORE the fix is "autofocus
     * lands on #shows-region instead of Wilco's Hide button," not a crash or a lost focus.
     * <p>
     * The middle row's performer is deliberately unfollowed (no artist seeded for this owner at
     * all), so it is excluded from every rendered list already, by #206's existing display filter --
     * this test is purely about which row {@code resolveVisibleShows} offers as Radiohead's
     * successor BEFORE that row is mutated out, not about display filtering itself (covered
     * elsewhere).
     */
    @Test
    void hidingARowSkipsAFilteredOutVenueSuccessorAndFocusesTheNextVisibleRow() throws Exception {
        String owner = "hide-successor-filtered@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long first = saveShow(showRepository, owner, "Radiohead", when, "Moody Center");
        saveShow(showRepository, owner, "Unfollowed Act", when.plusHours(1), "Some Club", "venue:example.com");
        saveShow(showRepository, owner, "Wilco", when.plusDays(1), "ACL Live");

        String body = mockMvc.perform(post("/shows/{id}/hide", first)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("aria-label=\"Hide Wilco[^\"]*\"[^>]*autofocus");
    }

    // ---- #202: comedy shows are distinguishable from a gig at a glance --------------------

    @Test
    void aComedyShowGetsAComedyChipOnTheShowsPage() throws Exception {
        String owner = "kind-comedy@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        saveShow(showRepository, owner, "Aziz Ansari", when, "Moody Center", Show.Kind.COMEDY);

        String body = mockMvc.perform(get("/")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Aziz Ansari");
        assertThat(body).containsPattern("class=\"chip comedy\"[^>]*>Comedy");
    }

    @Test
    void aMusicShowGetsNoComedyChipOnTheShowsPage() throws Exception {
        String owner = "kind-music@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        saveShow(showRepository, owner, "Wilco", when, "ACL Live", Show.Kind.MUSIC);

        String body = mockMvc.perform(get("/")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Wilco");
        assertThat(body)
                .as("a music show -- the common case -- doesn't get a redundant label")
                .doesNotContain("chip comedy");
    }
}
