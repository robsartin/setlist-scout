package com.robsartin.setlistscout.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PersonWorkEdgeRepository extends JpaRepository<PersonWorkEdge, Long> {

    List<PersonWorkEdge> findByOwnerAndArtistId(String owner, Long artistId);

    /**
     * DB-level idempotent upsert on {@code person_work_edge_unique}, mirroring
     * {@code ArtistEdgeRepository#insertIfAbsent}.
     *
     * <p>The constraint includes {@code source}, so this is only a no-op for a repeat of the exact
     * same assertion. A second source claiming the same role inserts a second, corroborating edge
     * -- V10's model-level fix for silent corroboration loss, applied here rather than
     * rediscovered later.
     */
    @Modifying
    @Query(value = """
            INSERT INTO person_work_edge (owner, artist_id, work_id, role, source, created_at)
            VALUES (:owner, :artistId, :workId, :role, :source, :createdAt)
            ON CONFLICT (owner, artist_id, work_id, role, source) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("owner") String owner,
                         @Param("artistId") Long artistId,
                         @Param("workId") Long workId,
                         @Param("role") String role,
                         @Param("source") String source,
                         @Param("createdAt") Instant createdAt);
}
