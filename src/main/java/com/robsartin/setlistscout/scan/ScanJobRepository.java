package com.robsartin.setlistscout.scan;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ScanJobRepository extends JpaRepository<ScanJob, Long> {
    Optional<ScanJob> findByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);
    List<ScanJob> findByOwnerAndArtistId(String owner, Long artistId);
    List<ScanJob> findByOwner(String owner);
    void deleteByOwnerAndArtistId(String owner, Long artistId);
    boolean existsByOwnerAndArtistIdAndSource(String owner, Long artistId, String source);

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
}
