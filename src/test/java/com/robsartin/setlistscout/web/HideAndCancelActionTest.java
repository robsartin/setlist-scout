package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
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

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the real {@code POST /shows/{id}/hide-and-cancel} action (issue #223) against a booted
 * context + Postgres, signed in as a test user -- the HTTP/htmx-level counterpart to {@link
 * com.robsartin.setlistscout.scan.HideAndCancelFlowTest}'s deeper real-listener/job-cancellation
 * proof. Mirrors {@code ShowHideActionsTest}'s exact style (issue #166), which this is additive to
 * and does not modify. Runs in CI (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class HideAndCancelActionTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private ArtistRepository artistRepository;

    private static Long saveShow(ShowRepository repo, String owner, String artistName,
                                  LocalDateTime eventDateTime, String venueName, Long artistId) {
        Show show = new Show(artistName, eventDateTime, venueName, "Austin", BigDecimal.TEN,
                "ticketmaster", null, Show.Kind.MUSIC);
        show.setOwner(owner);
        show.setArtistId(artistId);
        return repo.save(show).getId();
    }

    private Long saveSeedArtist(String owner, String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist).getId();
    }

    @Test
    void hideAndCancelHidesTheShowAndAnnouncesTheArtistWasDropped() throws Exception {
        String owner = "hide-cancel-announce@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long artistId = saveSeedArtist(owner, "Radiohead");
        Long showId = saveShow(showRepository, owner, "Radiohead", when, "Moody Center", artistId);

        String body = mockMvc.perform(post("/shows/{id}/hide-and-cancel", showId)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(showRepository.findById(showId).orElseThrow().getHiddenAt()).isNotNull();
        assertThat(artistRepository.findByIdAndOwner(artistId, owner).orElseThrow().getStatus())
                .isEqualTo(ArtistStatus.REMOVED);
        assertThat(body).containsPattern(
                "id=\"sr-status\"[^>]*hx-swap-oob=\"innerHTML\"|hx-swap-oob=\"innerHTML\"[^>]*id=\"sr-status\"");
        assertThat(body).contains("Hid Radiohead at Moody Center");
        assertThat(body).contains("and stopped following Radiohead");
        assertThat(countAutofocusElements(body))
                .as("exactly one autofocus even though the acted-on row's OWN two buttons are both gone now")
                .isEqualTo(1);
    }

    @Test
    void hideAndCancelWithNoArtistLinkedOnlyHidesTheShow() throws Exception {
        String owner = "hide-cancel-no-artist@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long showId = saveShow(showRepository, owner, "A Very Merry Symphony ft. Austin Symphony Orchestra",
                when, "Long Center", null);

        String body = mockMvc.perform(post("/shows/{id}/hide-and-cancel", showId)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(showRepository.findById(showId).orElseThrow().getHiddenAt()).isNotNull();
        assertThat(artistRepository.findByOwner(owner)).isEmpty();
        assertThat(body).as("no false claim of stopping to follow anything")
                .doesNotContain("stopped following");
        assertThat(countAutofocusElements(body)).isEqualTo(1);
    }

    @Test
    void hideAndCancelDoesNotTouchAnotherOwnersShowOrArtist() throws Exception {
        String owner = "hide-cancel-isolation-a@example.com";
        String otherOwner = "hide-cancel-isolation-b@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long theirArtistId = saveSeedArtist(otherOwner, "Not Yours");
        Long theirs = saveShow(showRepository, otherOwner, "Not Yours", when, "Their Venue", theirArtistId);

        mockMvc.perform(post("/shows/{id}/hide-and-cancel", theirs)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        assertThat(showRepository.findById(theirs).orElseThrow().getHiddenAt())
                .as("a foreign show id is a no-op, not a leak").isNull();
        assertThat(artistRepository.findById(theirArtistId).orElseThrow().getStatus())
                .as("the other owner's artist is untouched").isEqualTo(ArtistStatus.SEED);
    }

    @Test
    void aRowWithASuccessorFocusesTheNextRowsHideAndCancelButtonSpecifically() throws Exception {
        String owner = "hide-cancel-successor@example.com";
        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long firstArtistId = saveSeedArtist(owner, "Radiohead");
        Long secondArtistId = saveSeedArtist(owner, "Wilco");
        Long first = saveShow(showRepository, owner, "Radiohead", when, "Moody Center", firstArtistId);
        saveShow(showRepository, owner, "Wilco", when.plusDays(1), "ACL Live", secondArtistId);

        String body = mockMvc.perform(post("/shows/{id}/hide-and-cancel", first)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Issue #223's own risk: the successor row now renders TWO buttons (Hide, Hide & Stop
        // Following). Exactly one autofocus overall, and it must be the SAME action just performed
        // (hide-and-cancel), not the plain Hide button that happens to sit right next to it.
        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern("aria-label=\"Hide Wilco and stop following[^\"]*\"[^>]*autofocus");
    }
}
