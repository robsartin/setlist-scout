package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class ArtistImportRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rob@example.com";
    private static final String OTHER = "david@example.com";

    @Autowired private ArtistImportRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    /** @Transactional: insertIfAbsent is a @Modifying native query and needs an ambient transaction. */
    @Transactional
    void queue(String owner, String name, Instant dueAt) {
        repository.insertIfAbsent(owner, name, ArtistNameNormalizer.normalize(name), dueAt, Instant.now());
    }

    @Test
    @Transactional
    @DisplayName("queues a name as PENDING")
    void queuesPending() {
        repository.insertIfAbsent(OWNER, "Wilco", ArtistNameNormalizer.normalize("Wilco"),
                Instant.now(), Instant.now());

        assertThat(repository.countByOwnerAndStatus(OWNER, ArtistImportStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("re-queueing a name already PENDING is a no-op -- a double upload does not double the work")
    void pendingDuplicateIsSkipped() {
        String n = ArtistNameNormalizer.normalize("Wilco");
        assertThat(repository.insertIfAbsent(OWNER, "Wilco", n, Instant.now(), Instant.now())).isEqualTo(1);
        assertThat(repository.insertIfAbsent(OWNER, "wilco", n, Instant.now(), Instant.now())).isZero();

        assertThat(repository.countByOwnerAndStatus(OWNER, ArtistImportStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("a DONE row does NOT block re-queueing -- re-importing after a removal must work")
    void doneDoesNotBlockRequeue() {
        String n = ArtistNameNormalizer.normalize("Wilco");
        repository.insertIfAbsent(OWNER, "Wilco", n, Instant.now(), Instant.now());
        ArtistImport row = repository.findAll().get(0);
        row.setStatus(ArtistImportStatus.DONE);
        repository.save(row);

        assertThat(repository.insertIfAbsent(OWNER, "Wilco", n, Instant.now(), Instant.now())).isEqualTo(1);
        assertThat(repository.countByOwnerAndStatus(OWNER, ArtistImportStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("the same name for two owners is queued independently")
    void ownersAreIndependent() {
        String n = ArtistNameNormalizer.normalize("Wilco");
        assertThat(repository.insertIfAbsent(OWNER, "Wilco", n, Instant.now(), Instant.now())).isEqualTo(1);
        assertThat(repository.insertIfAbsent(OTHER, "Wilco", n, Instant.now(), Instant.now())).isEqualTo(1);

        assertThat(repository.countByOwnerAndStatus(OWNER, ArtistImportStatus.PENDING)).isEqualTo(1);
        assertThat(repository.countByOwnerAndStatus(OTHER, ArtistImportStatus.PENDING)).isEqualTo(1);
    }

    @Test
    @Transactional
    @DisplayName("claimDue returns only rows that are due, and marks them claimed")
    void claimDueRespectsDueTime() {
        Instant now = Instant.now();
        repository.insertIfAbsent(OWNER, "Due Now", ArtistNameNormalizer.normalize("Due Now"),
                now.minusSeconds(10), now);
        repository.insertIfAbsent(OWNER, "Not Yet", ArtistNameNormalizer.normalize("Not Yet"),
                now.plus(Duration.ofHours(1)), now);

        List<ArtistImport> claimed = repository.claimDue(now, now.minus(Duration.ofMinutes(5)), 20);

        assertThat(claimed).extracting(ArtistImport::getName).containsExactly("Due Now");
        assertThat(claimed.get(0).getClaimedAt()).isNotNull();
    }

    @Test
    @Transactional
    @DisplayName("a claimed row is not claimed again until its lease expires")
    void claimIsLeased() {
        Instant now = Instant.now();
        repository.insertIfAbsent(OWNER, "Wilco", ArtistNameNormalizer.normalize("Wilco"),
                now.minusSeconds(10), now);

        assertThat(repository.claimDue(now, now.minus(Duration.ofMinutes(5)), 20)).hasSize(1);
        assertThat(repository.claimDue(now, now.minus(Duration.ofMinutes(5)), 20))
                .as("still inside the lease window").isEmpty();
        assertThat(repository.claimDue(now, now.plus(Duration.ofMinutes(5)), 20))
                .as("lease expired -- reclaimable, so a crashed worker's row is not lost").hasSize(1);
    }

    @Test
    @Transactional
    @DisplayName("failed names are listable with their error")
    void failedAreListable() {
        Instant now = Instant.now();
        repository.insertIfAbsent(OWNER, "Bad Name", ArtistNameNormalizer.normalize("Bad Name"), now, now);
        ArtistImport row = repository.findAll().get(0);
        row.setStatus(ArtistImportStatus.FAILED);
        row.setLastError("boom");
        repository.save(row);

        List<ArtistImport> failed = repository.findByOwnerAndStatusOrderByNameAsc(OWNER, ArtistImportStatus.FAILED);
        assertThat(failed).extracting(ArtistImport::getName).containsExactly("Bad Name");
        assertThat(failed.get(0).getLastError()).isEqualTo("boom");
    }

    // #201: the admin queues page's aggregate queries -- COUNT(*) ... GROUP BY, never
    // findAll().size(), proven against real Postgres.

    @Test
    @Transactional
    @DisplayName("countGroupedByOwnerAndStatus aggregates pending/done/failed per owner")
    void countGroupedByOwnerAndStatusAggregatesPerOwner() {
        queue(OWNER, "Wilco", Instant.now());
        queue(OWNER, "Spoon", Instant.now());
        ArtistImport done = repository.findByOwnerAndStatusOrderByNameAsc(OWNER, ArtistImportStatus.PENDING).get(0);
        done.setStatus(ArtistImportStatus.DONE);
        repository.save(done);
        queue(OTHER, "Bad Name", Instant.now());
        ArtistImport failed = repository.findByOwnerAndStatusOrderByNameAsc(OTHER, ArtistImportStatus.PENDING).get(0);
        failed.setStatus(ArtistImportStatus.FAILED);
        failed.setLastError("boom");
        repository.save(failed);

        List<ImportOwnerStatusCount> counts = repository.countGroupedByOwnerAndStatus();

        assertThat(counts)
                .filteredOn(c -> c.getOwner().equals(OWNER) && c.getStatus() == ArtistImportStatus.PENDING)
                .extracting(ImportOwnerStatusCount::getCount).containsExactly(1L);
        assertThat(counts)
                .filteredOn(c -> c.getOwner().equals(OWNER) && c.getStatus() == ArtistImportStatus.DONE)
                .extracting(ImportOwnerStatusCount::getCount).containsExactly(1L);
        assertThat(counts)
                .filteredOn(c -> c.getOwner().equals(OTHER) && c.getStatus() == ArtistImportStatus.FAILED)
                .extracting(ImportOwnerStatusCount::getCount).containsExactly(1L);
    }

    @Test
    @Transactional
    @DisplayName("findByStatusOrderByOwnerAscNameAsc returns FAILED rows across every owner, with their error")
    void findByStatusOrderByOwnerAscNameAscReturnsFailedRowsAcrossOwners() {
        queue(OTHER, "Zeta Band", Instant.now());
        ArtistImport zeta = repository.findByOwnerAndStatusOrderByNameAsc(OTHER, ArtistImportStatus.PENDING).get(0);
        zeta.setStatus(ArtistImportStatus.FAILED);
        zeta.setLastError("network error");
        repository.save(zeta);

        queue(OWNER, "Alpha Band", Instant.now());
        ArtistImport alpha = repository.findByOwnerAndStatusOrderByNameAsc(OWNER, ArtistImportStatus.PENDING).get(0);
        alpha.setStatus(ArtistImportStatus.FAILED);
        alpha.setLastError("musicbrainz timeout");
        repository.save(alpha);

        queue(OWNER, "Still Pending", Instant.now());

        List<ArtistImport> failed = repository.findByStatusOrderByOwnerAscNameAsc(ArtistImportStatus.FAILED);

        // OTHER ("david@...") sorts before OWNER ("rob@...") -- owner is the primary sort key, so
        // this is NOT alphabetical-by-name order (Zeta before Alpha looks backwards until you
        // remember it's grouped by owner first).
        assertThat(failed).extracting(ArtistImport::getOwner).containsExactly(OTHER, OWNER);
        assertThat(failed).extracting(ArtistImport::getName).containsExactly("Zeta Band", "Alpha Band");
        assertThat(failed).extracting(ArtistImport::getLastError)
                .containsExactly("network error", "musicbrainz timeout");
    }
}
