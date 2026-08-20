package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface VenueScanJobRepository extends JpaRepository<VenueScanJob, Long> {

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
