package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.CatalogSeeder;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.JobStatusCount;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

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
class ExpandJobRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "expand-job-test@example.com";

    @Autowired
    private ExpandJobRepository expandJobRepository;

    /**
     * #172/#203: stubbed empty so this context's expand_job table contains ONLY what a @Test
     * method itself writes -- see ScanJobRepositoryTest#catalogSeeder for the full rationale
     * (CatalogSeeder's real-time ArtistActivated -> ExpandJobListener path is invisible to every
     * owner-scoped test, but not to the owner-less #201 admin-queue aggregates below).
     */
    @MockitoBean
    private CatalogSeeder catalogSeeder;

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

    @Test
    @DisplayName("the #201 admin-queue aggregates (countGroupedByStatus/countByNextDueAtLessThanEqual/"
            + "findFirstByOrderByNextDueAtAsc/findByStatusOrderByNextDueAtAsc) resolve correctly for "
            + "expand_job too -- smoke test, full behavioral coverage lives in "
            + "ScanJobRepositoryTest#countGroupedByStatus*/countByNextDueAtLessThanEqual*/find*")
    void adminQueueAggregatesResolveForExpandJobToo() {
        Instant now = Instant.now();
        ExpandJob overdue = new ExpandJob(1L, "lastfm", JobStatus.FAILED, 4, now.minus(2, ChronoUnit.DAYS));
        overdue.setOwner("shared:33333333-3333-3333-3333-333333333333");
        overdue.setLastError("MusicBrainz 503");
        expandJobRepository.save(overdue);

        ExpandJob dueSoon = new ExpandJob(2L, "musicbrainz", JobStatus.SCHEDULED, 0, now.plus(1, ChronoUnit.HOURS));
        dueSoon.setOwner(OWNER);
        expandJobRepository.save(dueSoon);

        Map<JobStatus, Long> byStatus = expandJobRepository.countGroupedByStatus().stream()
                .collect(Collectors.toMap(JobStatusCount::getStatus, JobStatusCount::getCount));
        assertThat(byStatus.get(JobStatus.FAILED)).isEqualTo(1L);
        assertThat(byStatus.get(JobStatus.SCHEDULED)).isEqualTo(1L);

        assertThat(expandJobRepository.countByNextDueAtLessThanEqual(now)).isEqualTo(1L);
        // Compare against a DB round-trip of this row, not the in-memory `overdue` local -- same
        // fix as AdminCrossAccountActionsTest's identical comment: Postgres timestamp columns are
        // microsecond precision (rounded, not truncated) while a JVM Instant.now() can carry
        // nanosecond precision (observed on CI's Linux runners, not locally on macOS). Both sides
        // of the equality below must come from the DB.
        Instant persistedOverdue = expandJobRepository.findById(overdue.getId()).orElseThrow().getNextDueAt();
        assertThat(expandJobRepository.findFirstByOrderByNextDueAtAsc()).isPresent()
                .get().extracting(ExpandJob::getNextDueAt).isEqualTo(persistedOverdue);

        List<ExpandJob> failed = expandJobRepository.findByStatusOrderByNextDueAtAsc(JobStatus.FAILED);
        assertThat(failed).extracting(ExpandJob::getOwner)
                .containsExactly("shared:33333333-3333-3333-3333-333333333333");
        assertThat(failed).extracting(ExpandJob::getLastError).containsExactly("MusicBrainz 503");
    }
}
