package com.robsartin.setlistscout.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ArtistImportRepository extends JpaRepository<ArtistImport, Long> {

    long countByOwnerAndStatus(String owner, ArtistImportStatus status);

    List<ArtistImport> findByOwnerAndStatusOrderByNameAsc(String owner, ArtistImportStatus status);

    /**
     * Queue one name, idempotently. {@code ON CONFLICT DO NOTHING} against the PARTIAL unique index
     * {@code artist_import_pending_key} — so re-uploading a file while its rows are still PENDING
     * queues nothing new, while a name whose earlier import is DONE can be queued again.
     * <p>
     * The DB-level conflict, rather than an {@code existsBy} pre-check, is this codebase's standing
     * rule for idempotent inserts: a read-then-write races, and inside a listener a resulting
     * constraint violation would poison the whole transaction.
     *
     * @return 1 if this call queued the name, 0 if it was already pending
     */
    @Modifying
    @Query(value = """
            INSERT INTO artist_import (owner, name, normalized_name, status, attempts, next_due_at, created_at)
            VALUES (:owner, :name, :normalizedName, 'PENDING', 0, :nextDueAt, :createdAt)
            ON CONFLICT DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("owner") String owner,
                        @Param("name") String name,
                        @Param("normalizedName") String normalizedName,
                        @Param("nextDueAt") Instant nextDueAt,
                        @Param("createdAt") Instant createdAt);

    /**
     * Claim a batch of due rows, mirroring {@code ScanJobRepository#claimDue}: {@code FOR UPDATE
     * SKIP LOCKED} so concurrent workers never contend, and a lease so a row whose worker died is
     * reclaimable rather than stuck forever.
     */
    @Modifying
    @Query(value = """
            UPDATE artist_import SET claimed_at = :now
            WHERE id IN (
                SELECT id FROM artist_import
                WHERE status = 'PENDING' AND next_due_at <= :now
                  AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
                ORDER BY next_due_at
                LIMIT :batch
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<ArtistImport> claimDue(@Param("now") Instant now,
                                 @Param("leaseCutoff") Instant leaseCutoff,
                                 @Param("batch") int batch);

    /**
     * Every FAILED import row across every owner, alphabetical by owner then name (#201) -- the
     * admin queues page's failed-work section. Mirrors {@code shared.JobRepository
     * #findByStatusOrderByNextDueAtAsc}'s "load only the small FAILED set" shape, not the whole
     * table.
     */
    List<ArtistImport> findByStatusOrderByOwnerAscNameAsc(ArtistImportStatus status);

    /**
     * Aggregate pending/done/failed counts per owner (#201): {@code COUNT(*) ... GROUP BY}, never
     * {@code findAll().size()} -- the same #176-shaped mistake {@code shared.JobRepository
     * #countGroupedByStatus}'s Javadoc guards against, applied to {@code artist_import}.
     */
    @Query("""
            SELECT a.owner AS owner, a.status AS status, COUNT(a) AS count
              FROM ArtistImport a
             GROUP BY a.owner, a.status
            """)
    List<ImportOwnerStatusCount> countGroupedByOwnerAndStatus();
}
