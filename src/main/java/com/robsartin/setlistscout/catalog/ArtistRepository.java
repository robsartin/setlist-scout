package com.robsartin.setlistscout.catalog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ArtistRepository extends JpaRepository<Artist, Long> {
    List<Artist> findByOwnerAndStatus(String owner, ArtistStatus status);
    List<Artist> findByOwnerAndStatusIn(String owner, List<ArtistStatus> statuses);
    List<Artist> findByStatusIn(List<ArtistStatus> statuses);
    List<Artist> findByOwnerAndSource(String owner, ArtistSource source);
    boolean existsByOwnerAndNameIgnoreCase(String owner, String name);
    Optional<Artist> findByIdAndOwner(Long id, String owner);

    /**
     * DB-level idempotent enqueue: relies on the {@code artist_owner_name_key} unique constraint
     * via {@code ON CONFLICT ... DO NOTHING} so a racing duplicate candidate for the same
     * (owner, name) is a silent no-op instead of a {@code DataIntegrityViolationException}. That
     * matters because {@code @ApplicationModuleListener} runs the whole listener body in one
     * transaction on an IDENTITY-keyed table: an uncaught constraint violation aborts the entire
     * transaction (Postgres "current transaction is aborted"), which then fails Modulith's own
     * AFTER_COMMIT completion write and leaves the event stuck for redelivery. See
     * catalog.CandidatePersistenceListener#on -- mirrors scan.ScanJobRepository#insertIfAbsent.
     */
    @Modifying
    @Query(value = """
            INSERT INTO artist (owner, name, source, status, discovered_via, note, created_at)
            VALUES (:owner, :name, :source, :status, :discoveredVia, :note, :createdAt)
            ON CONFLICT (owner, name) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("owner") String owner,
                         @Param("name") String name,
                         @Param("source") String source,
                         @Param("status") String status,
                         @Param("discoveredVia") String discoveredVia,
                         @Param("note") String note,
                         @Param("createdAt") Instant createdAt);

    /**
     * One row per (discoveredVia, source) pair among an owner's candidates in the given status --
     * the grouped-by-base-artist view for the Candidates page. The projection alias names
     * (via/source/count) must match CandidateGroupCount's getter properties.
     */
    @Query("""
        SELECT a.discoveredVia AS via, a.source AS source, COUNT(a) AS count
          FROM Artist a
         WHERE a.owner = :owner AND a.status = :status
         GROUP BY a.discoveredVia, a.source
        """)
    List<CandidateGroupCount> countByStatusGroupedByViaAndSource(String owner, ArtistStatus status);

    /** A page slice of one group's rows, for lazy load + "show more". */
    List<Artist> findByOwnerAndStatusAndDiscoveredViaAndSource(
        String owner, ArtistStatus status, String discoveredVia, ArtistSource source, Pageable pageable);

    /** All of one group's rows, for per-group bulk actions. */
    List<Artist> findByOwnerAndStatusAndDiscoveredViaAndSource(
        String owner, ArtistStatus status, String discoveredVia, ArtistSource source);

    /** Total pending candidates for the owner -- the nav badge. */
    long countByOwnerAndStatus(String owner, ArtistStatus status);
}
