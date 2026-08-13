package com.robsartin.setlistscout.catalog;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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
