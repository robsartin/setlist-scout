package com.robsartin.setlistscout.expansion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ExpandJobRepository extends JpaRepository<ExpandJob, Long> {
    Optional<ExpandJob> findByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);
    List<ExpandJob> findByOwnerAndArtistId(String owner, Long artistId);
    List<ExpandJob> findByOwner(String owner);
    void deleteByOwnerAndArtistId(String owner, Long artistId);
    boolean existsByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);

    /**
     * DB-level idempotent enqueue: relies on the {@code expand_job_owner_artist_id_source_key}
     * unique constraint via {@code ON CONFLICT ... DO NOTHING} so a racing redelivery (or
     * concurrent activation) for the same (owner, artist_id, source) is a silent no-op instead of
     * a {@code DataIntegrityViolationException}. See scan.ScanJobRepository#insertIfAbsent for why
     * that matters inside an {@code @ApplicationModuleListener}'s single per-source-loop transaction.
     */
    @Modifying
    @Query(value = """
            INSERT INTO expand_job (owner, artist_id, source, status, attempts, next_due_at)
            VALUES (:owner, :artistId, :source, 'SCHEDULED', 0, :nextDueAt)
            ON CONFLICT (owner, artist_id, source) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("owner") String owner,
                         @Param("artistId") Long artistId,
                         @Param("source") String source,
                         @Param("nextDueAt") Instant nextDueAt);

    /**
     * Atomically claims up to {@code batch} due, unclaimed-or-stale-leased rows for the poller
     * (PR4). See scan.ScanJobRepository#claimDue for the full rationale (SKIP LOCKED semantics,
     * lease-expiry re-claim, RETURNING-to-entity mapping) -- this mirrors it exactly for
     * expand_job.
     */
    @Modifying
    @Query(value = """
            UPDATE expand_job SET claimed_at = :now, status = 'RUNNING'
            WHERE id IN (
                SELECT id FROM expand_job
                WHERE next_due_at <= :now AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
                ORDER BY next_due_at
                LIMIT :batch
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<ExpandJob> claimDue(@Param("now") Instant now,
                              @Param("leaseCutoff") Instant leaseCutoff,
                              @Param("batch") int batch);
}
