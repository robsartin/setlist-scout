package com.robsartin.setlistscout.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface ArtistEdgeRepository extends JpaRepository<ArtistEdge, Long> {

    List<ArtistEdge> findByOwnerAndFromArtistId(String owner, Long fromArtistId);
    List<ArtistEdge> findByOwnerAndToArtistId(String owner, Long toArtistId);

    /**
     * DB-level idempotent upsert: relies on the {@code artist_edge_unique} constraint via
     * {@code ON CONFLICT ... DO NOTHING} so a racing or repeated assertion of the exact same
     * {@code (owner, from_artist_id, to_artist_id, type, source)} edge is a silent no-op instead
     * of a {@code DataIntegrityViolationException} -- the same durable-write invariant
     * (ADR-0024) as {@code ArtistRepository#insertIfAbsent} / {@code ScanJobRepository#insertIfAbsent}.
     *
     * <p>Critically, this constraint is scoped to include {@code source}: a second
     * {@code insertIfAbsent} for the same {@code (owner, from, to, type)} but a <em>different</em>
     * source is NOT a conflict -- it inserts a second, corroborating edge. That's the model-level
     * fix for the silent-corroboration-loss defect described in
     * {@code docs/explorations/2026-08-14-artist-graph-model.md} section 1.
     */
    @Modifying
    @Query(value = """
            INSERT INTO artist_edge (owner, from_artist_id, to_artist_id, type, source, note, weight, created_at)
            VALUES (:owner, :fromArtistId, :toArtistId, :type, :source, :note, :weight, :createdAt)
            ON CONFLICT (owner, from_artist_id, to_artist_id, type, source) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("owner") String owner,
                         @Param("fromArtistId") Long fromArtistId,
                         @Param("toArtistId") Long toArtistId,
                         @Param("type") String type,
                         @Param("source") String source,
                         @Param("note") String note,
                         @Param("weight") Double weight,
                         @Param("createdAt") Instant createdAt);
}
