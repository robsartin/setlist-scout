package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.JobStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives {@code POST /artists/{id}/scan-now} (issue #246) against a booted context + real
 * Postgres, end to end -- the same style as {@code web.AdminCrossAccountActionsTest} for the
 * whole-fleet {@code /scan-now}/{@code /admin/scan-now} escape hatch. Lives in {@code scan}
 * because that's where the endpoint itself lives: see {@code ShowController#scanNowForArtist}'s
 * Javadoc for why (a {@code catalog}<->{@code scan} module cycle {@code ModularityTests} would
 * reject, not a stylistic choice).
 * <p>
 * The point of this class, distinct from the mock-based {@code ShowControllerTest} and the
 * query-level {@code ScanJobRepositoryTest}, is proving the full stack agrees: a real HTTP POST,
 * through Spring Security, into the real {@code redueForArtist} query, with the DB state read
 * back afterward -- not a mocked/verified call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ArtistScanNowActionTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ScanJobRepository scanJobRepository;

    private Artist saveArtist(String owner, String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist);
    }

    private ScanJob seedFailedJob(String owner, Long artistId) {
        ScanJob job = new ScanJob(artistId, "ticketmaster", JobStatus.FAILED, 4,
                Instant.now().plus(Duration.ofDays(14)));
        job.setOwner(owner);
        job.setClaimedAt(Instant.now());
        return scanJobRepository.saveAndFlush(job);
    }

    @Test
    @DisplayName("issue #246: scanNowForArtist re-dues only the named artist's job (FAILED -> "
            + "SCHEDULED, attempts/claimed_at reset, version bumped) and redirects to /artists")
    void scanNowForArtistReduesOnlyThatArtistsJobAndRedirects() throws Exception {
        String owner = "rescan-happy-path@example.com";
        Artist target = saveArtist(owner, "Austin Symphony Orchestra");
        ScanJob targetJob = seedFailedJob(owner, target.getId());
        long v0 = scanJobRepository.findById(targetJob.getId()).orElseThrow().getVersion();

        mockMvc.perform(post("/artists/" + target.getId() + "/scan-now")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        ScanJob after = scanJobRepository.findById(targetJob.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getClaimedAt()).isNull();
        assertThat(after.getNextDueAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
        assertThat(after.getVersion()).isEqualTo(v0 + 1);
    }

    @Test
    @DisplayName("issue #246: scanNowForArtist leaves a DIFFERENT artist's job (same owner) "
            + "completely untouched -- the exact scoping assertion the issue calls out")
    void scanNowForArtistLeavesASecondArtistsJobUntouched() throws Exception {
        String owner = "rescan-second-artist@example.com";
        Artist target = saveArtist(owner, "Austin Symphony Orchestra");
        Artist other = saveArtist(owner, "A Completely Different Band");
        seedFailedJob(owner, target.getId());
        ScanJob otherJob = seedFailedJob(owner, other.getId());
        // Re-fetch rather than compare against the in-memory Instant: Postgres timestamp columns
        // are microsecond precision while a JVM Instant.now() can carry nanosecond precision (same
        // fix as AdminCrossAccountActionsTest's identical comment) -- both sides of the
        // "unchanged" comparison must come from the DB.
        ScanJob otherBefore = scanJobRepository.findById(otherJob.getId()).orElseThrow();
        Instant otherOriginalNextDueAt = otherBefore.getNextDueAt();
        long otherOriginalVersion = otherBefore.getVersion();

        mockMvc.perform(post("/artists/" + target.getId() + "/scan-now")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection());

        ScanJob otherAfter = scanJobRepository.findById(otherJob.getId()).orElseThrow();
        assertThat(otherAfter.getNextDueAt()).isEqualTo(otherOriginalNextDueAt);
        assertThat(otherAfter.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(otherAfter.getAttempts()).isEqualTo(4);
        assertThat(otherAfter.getVersion()).isEqualTo(otherOriginalVersion);
    }

    @Test
    @DisplayName("issue #246: scanNowForArtist 404s on another owner's artist id and leaves its job untouched")
    void scanNowForArtistRejectsAForeignArtistId() throws Exception {
        String victimOwner = "rescan-victim@example.com";
        Artist victimArtist = saveArtist(victimOwner, "Victim's Band");
        ScanJob victimJob = seedFailedJob(victimOwner, victimArtist.getId());
        Instant originalNextDueAt = scanJobRepository.findById(victimJob.getId()).orElseThrow().getNextDueAt();

        mockMvc.perform(post("/artists/" + victimArtist.getId() + "/scan-now")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", "attacker@example.com"))))
                .andExpect(status().isNotFound());

        ScanJob after = scanJobRepository.findById(victimJob.getId()).orElseThrow();
        assertThat(after.getNextDueAt()).isEqualTo(originalNextDueAt);
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getAttempts()).isEqualTo(4);
    }

    @Test
    @DisplayName("issue #246: scanNowForArtist 404s on an artist id that doesn't exist at all")
    void scanNowForArtistRejectsAnUnknownArtistId() throws Exception {
        mockMvc.perform(post("/artists/999999999/scan-now")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", "rescan-unknown-id@example.com"))))
                .andExpect(status().isNotFound());
    }
}
