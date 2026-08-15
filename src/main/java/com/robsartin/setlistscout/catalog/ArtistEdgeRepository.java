package com.robsartin.setlistscout.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface ArtistEdgeRepository extends JpaRepository<ArtistEdge, Long> {

    List<ArtistEdge> findByOwnerAndFromArtistId(String owner, Long fromArtistId);
    List<ArtistEdge> findByOwnerAndToArtistId(String owner, Long toArtistId);

    /**
     * Every outgoing edge from any of {@code fromArtistIds}, owner-scoped -- the batched,
     * set-based building block {@link ArtistConnectionsService} uses twice (once for the owner's
     * active artists, once for their 1-hop targets) to compute an aggregate 2-hop traversal in
     * two bounded queries instead of one recursive-CTE or per-artist call per starting artist
     * (issue #112). Uses the same {@code artist_edge_from_idx (owner, from_artist_id)} index as
     * {@link #findByOwnerAndFromArtistId}.
     */
    List<ArtistEdge> findByOwnerAndFromArtistIdIn(String owner, Collection<Long> fromArtistIds);

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

    /**
     * Read-only 2-hop (or N-hop) traversal from {@code startId}, owner-scoped, via a Postgres
     * recursive CTE (issue #111 -- validating the graph model from #109 against real data before
     * building a feature on it; shape recommended by
     * {@code docs/explorations/2026-08-14-artist-graph-model.md} section 2). Walks outgoing edges
     * only (from -> to), depth-first up to {@code maxDepth} hops, then collapses to one row per
     * reachable artist at its shortest depth -- an artist reachable via both a 1-hop and a 2-hop
     * path is reported once, at depth 1. The start artist itself is excluded from the result.
     *
     * <p>The anchor member's {@code :startId} is explicitly cast to {@code bigint} because
     * Postgres can't otherwise infer a type for a bare parameter in the non-recursive term of a
     * {@code UNION ALL} against the recursive term's {@code bigint} column.
     */
    @Query(value = """
            WITH RECURSIVE reachable(artist_id, depth) AS (
                SELECT CAST(:startId AS bigint), 0
                UNION ALL
                SELECT e.to_artist_id, r.depth + 1
                  FROM artist_edge e
                  JOIN reachable r ON e.from_artist_id = r.artist_id
                 WHERE r.depth < :maxDepth AND e.owner = :owner
            )
            SELECT artist_id AS artistId, min(depth) AS depth
              FROM reachable
             WHERE artist_id <> CAST(:startId AS bigint)
             GROUP BY artist_id
            """, nativeQuery = true)
    List<ReachableArtist> reachableWithin(@Param("owner") String owner,
                                           @Param("startId") Long startId,
                                           @Param("maxDepth") int maxDepth);
}
