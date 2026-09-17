package com.robsartin.setlistscout.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface WorkRepository extends JpaRepository<Work, Long> {

    Optional<Work> findByOwnerAndWikidataQid(String owner, String wikidataQid);

    List<Work> findByOwnerAndIdIn(String owner, Collection<Long> ids);

    /**
     * Every work whose normalized title is one of these -- the screening-match lookup (#289),
     * served by {@code work_match_idx}. Returns ALL films sharing a title rather than one, because
     * the caller has to see the ambiguity to refuse it: seven different films are called
     * "Titanic", and a query that silently returned the first would be the bug.
     */
    List<Work> findByOwnerAndNormalizedTitleIn(String owner, Collection<String> normalizedTitles);

    /**
     * DB-level idempotent upsert on {@code work_unique (owner, wikidata_qid)}, the same
     * durable-write guard (ADR-0024) as {@code ArtistRepository#insertIfAbsent}.
     *
     * <p>Idempotency is load-bearing rather than defensive: a filmography is re-read every time an
     * artist is refreshed and returns the same films each time, so without the conflict clause the
     * second refresh of any artist throws -- and under ADR-0024 that throw takes the whole
     * transaction, and anything waiting on its commit, with it.
     *
     * <p>DO NOTHING rather than DO UPDATE: a title or year that changed in Wikidata is a fact
     * worth re-reading deliberately, not something to overwrite silently on every poll.
     */
    @Modifying
    @Query(value = """
            INSERT INTO work (owner, wikidata_qid, title, normalized_title, release_year, created_at)
            VALUES (:owner, :wikidataQid, :title, :normalizedTitle, :releaseYear, :createdAt)
            ON CONFLICT (owner, wikidata_qid) DO NOTHING
            """, nativeQuery = true)
    void insertIfAbsent(@Param("owner") String owner,
                         @Param("wikidataQid") String wikidataQid,
                         @Param("title") String title,
                         @Param("normalizedTitle") String normalizedTitle,
                         @Param("releaseYear") Integer releaseYear,
                         @Param("createdAt") Instant createdAt);
}
