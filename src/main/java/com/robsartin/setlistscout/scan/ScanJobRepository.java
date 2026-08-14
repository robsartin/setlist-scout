package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScanJobRepository extends JpaRepository<ScanJob, Long> {
    Optional<ScanJob> findByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);
    List<ScanJob> findByOwnerAndArtistId(String owner, Long artistId);
    List<ScanJob> findByOwner(String owner);
    void deleteByOwnerAndArtistId(String owner, Long artistId);

    /**
     * DB-level idempotent enqueue: relies on the {@code scan_job_owner_artist_id_source_key}
     * unique constraint via {@code ON CONFLICT ... DO NOTHING} so a racing redelivery (or
     * concurrent activation) for the same (owner, artist_id, source) is a silent no-op instead of
     * a {@code DataIntegrityViolationException}. That matters because
     * {@code @ApplicationModuleListener} runs the whole per-source loop in one transaction on an
     * IDENTITY-keyed table: an uncaught constraint violation from one iteration would abort the
     * transaction for every subsequent statement (Postgres "current transaction is aborted"),
     * losing jobs for the other sources too. See scan.ScanJobListener#onArtistActivated.
     */
    @Modifying
    @Query(value = """
            INSERT INTO scan_job (owner, artist_id, source, status, attempts, next_due_at, location_fingerprint)
            VALUES (:owner, :artistId, :source, 'SCHEDULED', 0, :nextDueAt, :locationFingerprint)
            ON CONFLICT (owner, artist_id, source) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("owner") String owner,
                         @Param("artistId") Long artistId,
                         @Param("source") String source,
                         @Param("nextDueAt") Instant nextDueAt,
                         @Param("locationFingerprint") String locationFingerprint);

    /**
     * Atomically claims up to {@code batch} due, unclaimed-or-stale-leased rows for the poller
     * (PR4): {@code FOR UPDATE SKIP LOCKED} on the inner selection means two pollers racing this
     * query concurrently never claim the same row -- each just skips whatever the other already
     * has locked and picks the next candidate instead of blocking on it. A row is a candidate if
     * it's due ({@code next_due_at <= :now}) and either never claimed or its lease has expired
     * ({@code claimed_at < :leaseCutoff}), oldest-due first. {@code RETURNING *} maps straight
     * back onto the entity via this native query -- verified against real Postgres in
     * ScanJobRepositoryTest, since Spring Data's native-query-to-entity mapping for a RETURNING
     * clause isn't guaranteed by the framework docs the way a plain SELECT is.
     */
    @Modifying
    @Query(value = """
            UPDATE scan_job SET claimed_at = :now, status = 'RUNNING'
            WHERE id IN (
                SELECT id FROM scan_job
                WHERE next_due_at <= :now AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
                ORDER BY next_due_at
                LIMIT :batch
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<ScanJob> claimDue(@Param("now") Instant now,
                            @Param("leaseCutoff") Instant leaseCutoff,
                            @Param("batch") int batch);

    /**
     * Version-safe bulk re-due of every one of an owner's scan jobs: make them due-now and cleanly
     * claimable (SCHEDULED, attempts reset, lease cleared) at the current location, and bump
     * {@code version} so any poller holding one of these rows in-flight conflicts on its next
     * {@code save()} (ScanPoller catches that and skips its stale reschedule) instead of silently
     * overwriting this re-due. Used by ScanJobListener#onSettingsChanged and the manual "Scan now"
     * button (ShowController#scanNow). {@code @Transactional} makes this self-transactional
     * regardless of caller: ShowController#scanNow is a plain {@code @PostMapping} handler with no
     * ambient transaction, and this {@code @Modifying} bulk query needs one to execute (Spring
     * Data honors {@code @Transactional} on repository interface methods).
     */
    @Modifying
    @Transactional
    @Query(value = """
            UPDATE scan_job
               SET next_due_at = :now, status = 'SCHEDULED', attempts = 0, claimed_at = NULL,
                   location_fingerprint = :locationFingerprint, version = version + 1
             WHERE owner = :owner
            """, nativeQuery = true)
    int redueAll(@Param("owner") String owner,
                  @Param("now") Instant now,
                  @Param("locationFingerprint") String locationFingerprint);
}
