package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed round-trip of {@link VenueScanJob} through the real Postgres schema
 * (Flyway V25), not just the entity mapping -- proves {@code claimDue}'s claim-lease behavior
 * ({@code FOR UPDATE SKIP LOCKED}, per ADR-0023) against a real database. Mirrors
 * {@code catalog.ArtistImportRepositoryTest} and this package's own {@code ScanJobRepositoryTest}
 * (#206 Task 2).
 */
@SpringBootTest
@Testcontainers
class VenueScanJobRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "venue-scan-job-test@example.com";

    @Autowired
    private VenueScanJobRepository venueScanJobRepository;

    @Autowired
    private VenueRepository venueRepository;

    private Long venueId;
    private Long venueId2;

    @BeforeEach
    void clean() {
        venueScanJobRepository.deleteAll();
        venueRepository.deleteAll();
        venueId = createVenue(OWNER, "Venue One");
        venueId2 = createVenue(OWNER, "Venue Two");
    }

    /**
     * Builds the fixture venue via {@code Venue}'s package-private test-fixture constructor +
     * plain {@code save()}, NOT {@code VenueRepository#insertIfAbsent}: {@code insertIfAbsent}
     * returns {@code int} (an {@code executeUpdate}-routed {@code @Modifying} query, like
     * {@code ScanJobRepository#redueAll}), which needs a real ambient transaction and throws
     * {@code TransactionRequiredException} when called from this non-transactional
     * {@code @BeforeEach} (confirmed empirically). {@code save()} needs no such transaction --
     * it's a plain CRUD method, self-transactional via {@code SimpleJpaRepository}'s own
     * class-level {@code @Transactional} regardless of caller context.
     */
    Long createVenue(String owner, String name) {
        Venue venue = new Venue(owner, name, ArtistNameNormalizer.normalize(name), "https://example.com/events");
        return venueRepository.save(venue).getId();
    }

    Long insertJob(String owner, Long venueId, Instant nextDueAt, Instant claimedAt) {
        VenueScanJob job = new VenueScanJob(owner, venueId, JobStatus.SCHEDULED, 0, nextDueAt);
        job.setClaimedAt(claimedAt);
        return venueScanJobRepository.save(job).getId();
    }

    // No @Transactional on these @Test methods, deliberately -- ScanJobRepositoryTest's own
    // claimDue tests document why: claimDue returns List<Entity> via RETURNING, which Spring Data
    // routes through getResultList (not executeUpdate), so it needs no ambient transaction. More
    // importantly here: wrapping the test in @Transactional would make insertJob's save() and the
    // later claimDue() call share ONE Hibernate persistence context/L1 cache. claimDue's native
    // UPDATE...RETURNING then finds the row's Long id already managed from save() and -- by
    // Hibernate's identity-map contract -- hands back that SAME stale Java instance instead of a
    // fresh one built from the RETURNING row, so getClaimedAt() reads the pre-claim value (null).
    // Confirmed by mutation: this exact test failed with "Expecting actual not to be null" when
    // @Transactional was present, even though the claim itself (right row, right id) was correct
    // at the SQL level the whole time. Each call getting its own throwaway transaction/persistence
    // context (no ambient @Transactional) avoids the collision entirely.

    @Test
    @DisplayName("claimDue returns only due, unclaimed rows and stamps claimed_at")
    void claimsOnlyDueUnclaimedRows() {
        Long due = insertJob(OWNER, venueId, Instant.now().minusSeconds(60), null);
        Long notDue = insertJob(OWNER, venueId2, Instant.now().plusSeconds(600), null);

        List<VenueScanJob> claimed = venueScanJobRepository.claimDue(
                Instant.now(), Instant.now().minusSeconds(300), 10);

        assertThat(claimed).extracting(VenueScanJob::getId).containsExactly(due).doesNotContain(notDue);
        assertThat(claimed.get(0).getClaimedAt()).isNotNull();
    }

    @Test
    @DisplayName("a row claimed within the lease window is not re-claimed")
    void doesNotReclaimWithinLease() {
        insertJob(OWNER, venueId, Instant.now().minusSeconds(60), Instant.now().minusSeconds(10));
        assertThat(venueScanJobRepository.claimDue(
                Instant.now(), Instant.now().minusSeconds(300), 10)).isEmpty();
    }

    @Test
    @DisplayName("a row whose lease has expired is reclaimable, so a dead worker does not strand it")
    void reclaimsAfterLeaseExpiry() {
        insertJob(OWNER, venueId, Instant.now().minusSeconds(60), Instant.now().minusSeconds(600));
        assertThat(venueScanJobRepository.claimDue(
                Instant.now(), Instant.now().minusSeconds(300), 10)).hasSize(1);
    }
}
