package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface VenueScanJobRepository extends JpaRepository<VenueScanJob, Long> {

    /**
     * Enqueues one job for a just-added venue, idempotently (#206 Task 6 -- no job-creation path
     * existed before this task). {@code ON CONFLICT (owner, venue_id) DO NOTHING} against the
     * {@code venue_scan_job_venue} unique index (V25) -- confirmed the ONLY unique constraint on
     * this table before writing this query (per CLAUDE.md/issue #219: {@code ON CONFLICT}
     * suppresses only the constraint it names). Status is the literal {@code 'SCHEDULED'}, not a
     * parameter: {@code VenueScanJobRepository#claimDue} hard-filters on {@code status =
     * 'SCHEDULED'}, so anything else here would make the row permanently unclaimable. {@code
     * nextDueAt} is passed as "now" so the very next poller tick can claim it -- mirrors {@code
     * ScanJobRepository#insertIfAbsent}'s exact shape (hardcoded status/attempts, single reused
     * Instant, {@code void} return -- callers care whether the row exists after this call, not
     * whether this specific call is what created it).
     */
    @Modifying
    @Query(value = """
            INSERT INTO venue_scan_job (owner, venue_id, status, attempts, next_due_at, created_at)
            VALUES (:owner, :venueId, 'SCHEDULED', 0, :now, :now)
            ON CONFLICT (owner, venue_id) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("owner") String owner, @Param("venueId") Long venueId, @Param("now") Instant now);

    /** Every venue_scan_job row for one owner (#206 Task 6): the /venues page's last-scanned column reads this, one load, not one query per row. */
    List<VenueScanJob> findByOwner(String owner);

    /**
     * Atomically claims up to {@code batch} due, unclaimed-or-stale-leased SCHEDULED rows for the
     * poller (a later task): {@code FOR UPDATE SKIP LOCKED} (ADR-0023) so concurrent workers never
     * contend -- each just skips whatever another worker already has locked and picks the next
     * candidate instead of blocking on it. A row is a candidate if it's SCHEDULED, due
     * ({@code next_due_at <= :now}), and either never claimed or its lease has expired
     * ({@code claimed_at < :leaseCutoff}), oldest-due first. Mirrors
     * {@code catalog.ArtistImportRepository#claimDue} / {@code scan.ScanJobRepository#claimDue}.
     */
    @Modifying
    @Query(value = """
            UPDATE venue_scan_job SET claimed_at = :now
            WHERE id IN (
                SELECT id FROM venue_scan_job
                WHERE status = 'SCHEDULED' AND next_due_at <= :now
                  AND (claimed_at IS NULL OR claimed_at < :leaseCutoff)
                ORDER BY next_due_at
                LIMIT :batch
                FOR UPDATE SKIP LOCKED
            )
            RETURNING *
            """, nativeQuery = true)
    List<VenueScanJob> claimDue(@Param("now") Instant now,
                                 @Param("leaseCutoff") Instant leaseCutoff,
                                 @Param("batch") int batch);
}
