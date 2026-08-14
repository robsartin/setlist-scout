package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/**
 * Testcontainers-backed round-trip of ScanJob through the real Postgres schema (Flyway V6),
 * not just the entity mapping -- proves the (owner, artist_id, source) unique constraint is
 * actually enforced by the database, not merely assumed. Boots the full context (like
 * ApplicationContextSmokeTest) rather than an @ApplicationModuleTest slice, for consistency with
 * this suite's other Testcontainers-backed repository tests.
 */
@SpringBootTest
@Testcontainers
class ScanJobRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "scan-job-test@example.com";

    @Autowired
    private ScanJobRepository scanJobRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * claimDue has no owner filter (it's a poller-wide claim, not scoped to one caller), so a
     * stray due row left behind by another test in this class would silently pollute the
     * claimDue tests' counts. Starting every test from an empty table keeps them independent of
     * execution order.
     */
    @BeforeEach
    void clearScanJobs() {
        scanJobRepository.deleteAll();
    }

    @Test
    @DisplayName("save + findByOwnerAndArtistIdAndSource round-trips all fields")
    void saveAndFindRoundTripsAllFields() {
        Instant nextDueAt = Instant.now().plus(1, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MICROS);
        ScanJob job = new ScanJob(42L, "ticketmaster", JobStatus.SCHEDULED, 0, nextDueAt);
        job.setOwner(OWNER);
        job.setLastError("boom");
        job.setLastRunAt(Instant.now().minus(1, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MICROS));
        job.setClaimedAt(Instant.now().minus(5, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.MICROS));

        scanJobRepository.save(job);

        Optional<ScanJob> found = scanJobRepository.findByOwnerAndArtistIdAndSource(OWNER, 42L, "ticketmaster");
        assertThat(found).isPresent();
        ScanJob loaded = found.get();
        assertThat(loaded.getId()).isNotNull();
        assertThat(loaded.getOwner()).isEqualTo(OWNER);
        assertThat(loaded.getArtistId()).isEqualTo(42L);
        assertThat(loaded.getSource()).isEqualTo("ticketmaster");
        assertThat(loaded.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(loaded.getAttempts()).isEqualTo(0);
        assertThat(loaded.getLastError()).isEqualTo("boom");
        assertThat(loaded.getLastRunAt()).isEqualTo(job.getLastRunAt());
        assertThat(loaded.getNextDueAt()).isEqualTo(nextDueAt);
        assertThat(loaded.getClaimedAt()).isEqualTo(job.getClaimedAt());
    }

    @Test
    @DisplayName("a stale save is rejected once another writer has bumped the version")
    void staleSaveThrowsOptimisticLockingFailure() {
        ScanJob job = new ScanJob(1L, "ticketmaster", JobStatus.SCHEDULED, 0, Instant.now());
        job.setOwner(OWNER);
        Long id = scanJobRepository.saveAndFlush(job).getId();
        scanJobRepository.flush();

        // Two independent loads of the same row (simulating poller-in-flight vs. a SettingsChanged re-due).
        ScanJob loadA = scanJobRepository.findById(id).orElseThrow();
        ScanJob loadB = scanJobRepository.findById(id).orElseThrow();
        entityManager.detach(loadA);
        entityManager.detach(loadB);

        // Writer B commits first -> version bumps in the DB.
        loadB.setNextDueAt(Instant.now().plusSeconds(3600));
        scanJobRepository.saveAndFlush(loadB);

        // Writer A now holds a stale version -> its save must be rejected, not silently win.
        loadA.setNextDueAt(Instant.now().plusSeconds(999));
        assertThatThrownBy(() -> scanJobRepository.saveAndFlush(loadA))
                .isInstanceOf(org.springframework.orm.ObjectOptimisticLockingFailureException.class);
    }

    @Test
    @DisplayName("the (owner, artist_id, source) unique constraint is enforced")
    void uniqueConstraintIsEnforced() {
        ScanJob first = new ScanJob(7L, "bandsintown", JobStatus.SCHEDULED, 0, Instant.now());
        first.setOwner(OWNER);
        scanJobRepository.saveAndFlush(first);

        ScanJob duplicate = new ScanJob(7L, "bandsintown", JobStatus.SCHEDULED, 0, Instant.now());
        duplicate.setOwner(OWNER);

        assertThatThrownBy(() -> scanJobRepository.saveAndFlush(duplicate))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("redueAll makes all of an owner's jobs due-now, claimable, and bumps version")
    // redueAll returns int, so Spring Data routes it through EntityManager#executeUpdate (unlike
    // claimDue's List<ScanJob>+RETURNING, which goes through getResultList and needs no ambient
    // transaction). executeUpdate is a genuine JPA requirement for an active transaction -- in
    // production that's supplied by ScanJobListener#onSettingsChanged running inside its own
    // @ApplicationModuleListener transaction; here we supply one explicitly.
    @Transactional
    void redueAllResetsJobsAndBumpsVersion() {
        ScanJob job = new ScanJob(1L, "ticketmaster", JobStatus.FAILED, 4,
                Instant.now().plus(java.time.Duration.ofDays(14)));
        job.setOwner(OWNER);
        job.setClaimedAt(Instant.now());
        Long id = scanJobRepository.saveAndFlush(job).getId();
        long v0 = scanJobRepository.findById(id).orElseThrow().getVersion();

        Instant now = Instant.now();
        int updated = scanJobRepository.redueAll(OWNER, now);
        assertThat(updated).isEqualTo(1);

        entityManager.clear();
        ScanJob after = scanJobRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getClaimedAt()).isNull();
        assertThat(after.getNextDueAt()).isCloseTo(now, within(1, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(after.getVersion()).isEqualTo(v0 + 1);
    }

    @Test
    @DisplayName("redueAll commits even with no ambient transaction, proving it is self-transactional "
            + "-- this is the path ShowController#scanNow calls directly from a plain @PostMapping handler")
    void redueAllCommitsWithoutAmbientTransaction() {
        ScanJob job = new ScanJob(2L, "bandsintown", JobStatus.FAILED, 3,
                Instant.now().plus(java.time.Duration.ofDays(7)));
        job.setOwner(OWNER);
        Long id = scanJobRepository.saveAndFlush(job).getId();

        Instant now = Instant.now();
        int updated = scanJobRepository.redueAll(OWNER, now);
        assertThat(updated).isEqualTo(1);

        ScanJob after = scanJobRepository.findById(id).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getNextDueAt()).isCloseTo(now, within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @DisplayName("claimDue claims due, unclaimed rows: sets claimed_at + status RUNNING, and returns them")
    void claimDueClaimsDueUnclaimedRows() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseCutoff = now.minus(5, ChronoUnit.MINUTES);
        ScanJob due = persist(1L, "ticketmaster", now.minus(1, ChronoUnit.MINUTES), null, JobStatus.SCHEDULED);

        List<ScanJob> claimed = scanJobRepository.claimDue(now, leaseCutoff, 10);

        assertThat(claimed).extracting(ScanJob::getId).containsExactly(due.getId());
        ScanJob returned = claimed.get(0);
        assertThat(returned.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(returned.getClaimedAt()).isEqualTo(now);

        ScanJob reloaded = scanJobRepository.findById(due.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(reloaded.getClaimedAt()).isEqualTo(now);
    }

    @Test
    @DisplayName("claimDue does not claim a row that isn't due yet")
    void claimDueSkipsNotYetDueRows() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseCutoff = now.minus(5, ChronoUnit.MINUTES);
        ScanJob notYetDue = persist(2L, "ticketmaster", now.plus(1, ChronoUnit.HOURS), null, JobStatus.SCHEDULED);

        List<ScanJob> claimed = scanJobRepository.claimDue(now, leaseCutoff, 10);

        assertThat(claimed).extracting(ScanJob::getId).doesNotContain(notYetDue.getId());
        ScanJob reloaded = scanJobRepository.findById(notYetDue.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(reloaded.getClaimedAt()).isNull();
    }

    @Test
    @DisplayName("claimDue skips a freshly-leased row but re-claims one whose lease has expired")
    void claimDueRespectsLeaseCutoff() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseCutoff = now.minus(5, ChronoUnit.MINUTES);
        ScanJob freshlyLeased = persist(3L, "ticketmaster", now.minus(10, ChronoUnit.MINUTES),
                now.minus(1, ChronoUnit.MINUTES), JobStatus.RUNNING);
        ScanJob expiredLease = persist(4L, "ticketmaster", now.minus(10, ChronoUnit.MINUTES),
                now.minus(10, ChronoUnit.MINUTES), JobStatus.RUNNING);

        List<ScanJob> claimed = scanJobRepository.claimDue(now, leaseCutoff, 10);

        assertThat(claimed).extracting(ScanJob::getId)
                .contains(expiredLease.getId())
                .doesNotContain(freshlyLeased.getId());

        ScanJob reloadedFresh = scanJobRepository.findById(freshlyLeased.getId()).orElseThrow();
        assertThat(reloadedFresh.getClaimedAt()).isEqualTo(now.minus(1, ChronoUnit.MINUTES));

        ScanJob reloadedExpired = scanJobRepository.findById(expiredLease.getId()).orElseThrow();
        assertThat(reloadedExpired.getClaimedAt()).isEqualTo(now);
        assertThat(reloadedExpired.getStatus()).isEqualTo(JobStatus.RUNNING);
    }

    @Test
    @DisplayName("claimDue respects the batch limit, leaving the rest unclaimed")
    void claimDueRespectsBatchLimit() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseCutoff = now.minus(5, ChronoUnit.MINUTES);
        ScanJob a = persist(5L, "ticketmaster", now.minus(3, ChronoUnit.MINUTES), null, JobStatus.SCHEDULED);
        ScanJob b = persist(6L, "ticketmaster", now.minus(2, ChronoUnit.MINUTES), null, JobStatus.SCHEDULED);
        ScanJob c = persist(7L, "ticketmaster", now.minus(1, ChronoUnit.MINUTES), null, JobStatus.SCHEDULED);

        List<ScanJob> claimed = scanJobRepository.claimDue(now, leaseCutoff, 2);

        assertThat(claimed).hasSize(2);
        List<ScanJob> all = scanJobRepository.findAllById(List.of(a.getId(), b.getId(), c.getId()));
        long runningCount = all.stream().filter(j -> j.getStatus() == JobStatus.RUNNING).count();
        long scheduledCount = all.stream().filter(j -> j.getStatus() == JobStatus.SCHEDULED).count();
        assertThat(runningCount).isEqualTo(2);
        assertThat(scheduledCount).isEqualTo(1);
    }

    @Test
    @DisplayName("two concurrent claimDue calls over overlapping due rows never claim the same row (SKIP LOCKED)")
    void claimDueIsSafeUnderConcurrency() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant leaseCutoff = now.minus(5, ChronoUnit.MINUTES);
        List<Long> availableIds = List.of(
                persist(10L, "ticketmaster", now.minus(3, ChronoUnit.MINUTES), null, JobStatus.SCHEDULED).getId(),
                persist(11L, "ticketmaster", now.minus(2, ChronoUnit.MINUTES), null, JobStatus.SCHEDULED).getId(),
                persist(12L, "ticketmaster", now.minus(1, ChronoUnit.MINUTES), null, JobStatus.SCHEDULED).getId());

        // Each thread races the other via its own connection/transaction (a plain repository
        // call outside any surrounding @Transactional gets its own), both gated on one latch so
        // their claimDue statements overlap in Postgres rather than running sequentially -- the
        // condition SKIP LOCKED is meant to handle.
        CountDownLatch startLatch = new CountDownLatch(1);
        Callable<List<Long>> claimTask = () -> {
            startLatch.await();
            return scanJobRepository.claimDue(now, leaseCutoff, 3).stream()
                    .map(ScanJob::getId)
                    .collect(Collectors.toList());
        };
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<List<Long>> future1 = executor.submit(claimTask);
            Future<List<Long>> future2 = executor.submit(claimTask);
            startLatch.countDown();

            List<Long> claimedByThread1 = future1.get(10, TimeUnit.SECONDS);
            List<Long> claimedByThread2 = future2.get(10, TimeUnit.SECONDS);

            List<Long> union = new ArrayList<>(claimedByThread1);
            union.addAll(claimedByThread2);
            assertThat(union).as("no row claimed by both threads").doesNotHaveDuplicates();
            assertThat(union.size()).as("total claimed does not exceed what was available")
                    .isLessThanOrEqualTo(availableIds.size());
            assertThat(availableIds).as("every available row ended up claimed by exactly one thread")
                    .containsExactlyInAnyOrderElementsOf(union);
        } finally {
            executor.shutdownNow();
        }
    }

    private ScanJob persist(Long artistId, String source, Instant nextDueAt, Instant claimedAt, JobStatus status) {
        ScanJob job = new ScanJob(artistId, source, status, 0, nextDueAt);
        job.setOwner(OWNER);
        job.setClaimedAt(claimedAt);
        return scanJobRepository.save(job);
    }
}
