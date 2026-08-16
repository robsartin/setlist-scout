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
        Show show = new Show(artistName, eventDateTime, venueName, "Austin", null, "ticketmaster", null);
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
}
