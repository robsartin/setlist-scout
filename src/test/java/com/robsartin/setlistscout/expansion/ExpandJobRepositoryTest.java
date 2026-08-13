package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.shared.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Testcontainers-backed round-trip of ExpandJob through the real Postgres schema (Flyway V7),
 * not just the entity mapping -- proves the (owner, artist_id, source) unique constraint is
 * actually enforced by the database, not merely assumed. Boots the full context (like
 * ApplicationContextSmokeTest) rather than an @ApplicationModuleTest slice, mirroring
 * ScanJobRepositoryTest.
 */
@SpringBootTest
@Testcontainers
class ExpandJobRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /** OAuth client registration needs a client-id/secret to initialise; application.yml has no default. */
    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-client-secret");
    }

    private static final String OWNER = "expand-job-test@example.com";

    @Autowired
    private ExpandJobRepository expandJobRepository;

    /**
     * claimDue has no owner filter (poller-wide claim), so start every test from an empty table
     * -- see ScanJobRepositoryTest#clearScanJobs for the full rationale.
     */
    @BeforeEach
    void clearExpandJobs() {
        expandJobRepository.deleteAll();
    }

    @Test
    @DisplayName("save + findByOwnerAndArtistIdAndSource round-trips all fields")
    void saveAndFindRoundTripsAllFields() {
        Instant nextDueAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        ExpandJob job = new ExpandJob(42L, "lastfm", JobStatus.SCHEDULED, 0, nextDueAt);
        job.setOwner(OWNER);
        job.setLastError("boom");
        job.setLastRunAt(Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS));
        job.setClaimedAt(Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS));

        expandJobRepository.save(job);

        Optional<ExpandJob> found = expandJobRepository.findByOwnerAndArtistIdAndSource(OWNER, 42L, "lastfm");
        assertThat(found).isPresent();
        ExpandJob loaded = found.get();
        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getOwner()).isEqualTo(OWNER);
        assertThat(loaded.getArtistId()).isEqualTo(42L);
        assertThat(loaded.getSource()).isEqualTo("lastfm");
        assertThat(loaded.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(loaded.getAttempts()).isEqualTo(0);
        assertThat(loaded.getLastError()).isEqualTo("boom");
        assertThat(loaded.getLastRunAt()).isEqualTo(job.getLastRunAt());
        assertThat(loaded.getNextDueAt()).isEqualTo(nextDueAt);
        assertThat(loaded.getClaimedAt()).isEqualTo(job.getClaimedAt());
    }

    @Test
    @DisplayName("the (owner, artist_id, source) unique constraint is enforced")
    void uniqueConstraintIsEnforced() {
        ExpandJob first = new ExpandJob(7L, "discogs", JobStatus.SCHEDULED, 0, Instant.now());
        first.setOwner(OWNER);
        expandJobRepository.saveAndFlush(first);

        ExpandJob duplicate = new ExpandJob(7L, "discogs", JobStatus.SCHEDULED, 0, Instant.now());
        duplicate.setOwner(OWNER);

        assertThatThrownBy(() -> expandJobRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("redueAll commits even with no ambient transaction, proving it is self-transactional "
            + "-- this is the path ReviewController#expandNow calls directly from a plain @PostMapping handler")
    void redueAllCommitsWithoutAmbientTransaction() {
        ExpandJob job = new ExpandJob(3L, "discogs", JobStatus.FAILED, 2,
                Instant.now().plus(java.time.Duration.ofDays(7)));
        job.setOwner(OWNER);
        Long id = expandJobRepository.saveAndFlush(job).getId();

        Instant now = Instant.now();
        int updated = expandJobRepository.redueAll(OWNER, now);
        assertThat(updated).isEqualTo(1);

        ExpandJob after = expandJobRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getNextDueAt()).isCloseTo(now, org.assertj.core.api.Assertions.within(1, ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("claimDue claims a due, unclaimed row and skips a not-yet-due one "
            + "(smoke test -- full behavioral coverage lives in ScanJobRepositoryTest#claimDue*)")
    void claimDueClaimsDueRowsAndSkipsNotYetDueOnes() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseCutoff = now.minus(5, ChronoUnit.MINUTES);

        ExpandJob due = new ExpandJob(1L, "lastfm", JobStatus.SCHEDULED, 0, now.minus(1, ChronoUnit.MINUTES));
        due.setOwner(OWNER);
        expandJobRepository.save(due);

        ExpandJob notYetDue = new ExpandJob(2L, "lastfm", JobStatus.SCHEDULED, 0, now.plus(1, ChronoUnit.HOURS));
        notYetDue.setOwner(OWNER);
        expandJobRepository.save(notYetDue);

        List<ExpandJob> claimed = expandJobRepository.claimDue(now, leaseCutoff, 10);

        assertThat(claimed).extracting(ExpandJob::getId)
                .contains(due.getId())
                .doesNotContain(notYetDue.getId());
        ExpandJob reloadedDue = expandJobRepository.findById(due.getId()).orElseThrow();
        assertThat(reloadedDue.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(reloadedDue.getClaimedAt()).isEqualTo(now);
        ExpandJob reloadedNotYetDue = expandJobRepository.findById(notYetDue.getId()).orElseThrow();
        assertThat(reloadedNotYetDue.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(reloadedNotYetDue.getClaimedAt()).isNull();
    }
}
