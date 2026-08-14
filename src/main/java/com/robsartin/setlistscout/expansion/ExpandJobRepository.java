package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.shared.JobRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

public interface ExpandJobRepository extends JobRepository<ExpandJob> {

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

    /**
     * Version-safe bulk re-due of every one of an owner's expand jobs (see ScanJobRepository#redueAll;
     * expansion isn't location-sensitive so there's no fingerprint). Used by the manual "Expand now"
     * button (ReviewController#expandNow). {@code @Transactional} makes this self-transactional
     * regardless of caller: ReviewController#expandNow is a plain {@code @PostMapping} handler with
     * no ambient transaction, and this {@code @Modifying} bulk query needs one to execute.
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE expand_job
               SET next_due_at = :now, status = 'SCHEDULED', attempts = 0, claimed_at = NULL,
                   version = version + 1
             WHERE owner = :owner
            """, nativeQuery = true)
    int redueAll(@Param("owner") String owner, @Param("now") Instant now);
}
