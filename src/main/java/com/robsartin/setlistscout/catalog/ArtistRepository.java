package com.robsartin.setlistscout.catalog;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ArtistRepository extends JpaRepository<Artist, Long> {
    List<Artist> findByOwnerAndStatus(String owner, ArtistStatus status);
    List<Artist> findByOwnerAndStatusIn(String owner, List<ArtistStatus> statuses);
    List<Artist> findByStatusIn(List<ArtistStatus> statuses);
    List<Artist> findByOwnerAndSource(String owner, ArtistSource source);
    boolean existsByOwnerAndNameIgnoreCase(String owner, String name);
    Optional<Artist> findByIdAndOwner(Long id, String owner);

    /** Batch id -> name resolution for the graph-tool view (issue #111), owner-scoped. */
    List<Artist> findByOwnerAndIdIn(String owner, Collection<Long> ids);

    /**
     * The indexed replacement for scanning every row and re-normalizing it in Java (#176). Backed by
     * {@code idx_artist_owner_normalized_name}. Deliberately status-unfiltered, same as the scan it
     * replaces: a normalized-name match against a REJECTED row is exactly the case {@link
     * ArtistNameMatcher} must catch (a rejected artist reappearing under a new spelling).
     * <p>
     * {@code findFirst}, not a unique lookup: {@code (owner, normalized_name)} is deliberately NOT
     * unique yet (see {@code V19__add_artist_normalized_name}), so a pre-existing duplicate variant
     * would make a single-result query throw. This preserves the exact semantics of the
     * {@code .findFirst()} it replaces.
     * <p>
     * No {@code OrderBy}: if {@code (owner, normalized_name)} ever has more than one matching row,
     * which one this returns is unspecified -- whatever order Postgres happens to return, not
     * guaranteed stable across calls. Harmless today: the one such pair known to exist is both
     * REJECTED, and every caller here branches only on {@code status}, never on row identity, so
     * either row answers "is this a rejected reappearance" the same way. Would matter if a future
     * caller needed a SPECIFIC row (e.g. its {@code createdAt}) rather than just "does a match
     * exist and what's its status."
     */
    Optional<ArtistNameStatusView> findFirstByOwnerAndNormalizedName(String owner, String normalizedName);

    /**
     * Case-SENSITIVE exact-match lookup used by {@code RelationDiscoveredListener} to resolve the
     * to-artist id right after an {@link #insertIfAbsent} upsert. Deliberately not
     * case-insensitive: {@code insertIfAbsent}'s {@code ON CONFLICT (owner, name)} constraint is
     * itself case-sensitive (see {@code ArtistRepositoryTest#uniqueConstraintIsCaseSensitive}), so
     * an exact match on the same {@code name} string just passed to {@code insertIfAbsent} is
     * guaranteed to resolve exactly the row that call just created or conflicted against -- a
     * case-insensitive lookup could instead hit an unrelated case-variant duplicate for the same
     * owner and return more than one row, throwing {@code IncorrectResultSizeDataAccessException}
     * and poisoning this listener's transaction (the exact ADR-0024 failure mode this avoids).
     */
    Optional<Artist> findByOwnerAndName(String owner, String name);

    /**
     * DB-level idempotent enqueue: relies on the {@code artist_owner_name_key} unique constraint
     * via {@code ON CONFLICT ... DO NOTHING} so a racing duplicate candidate for the same
     * (owner, name) is a silent no-op instead of a {@code DataIntegrityViolationException}. That
     * matters because {@code @ApplicationModuleListener} runs the whole listener body in one
     * transaction on an IDENTITY-keyed table: an uncaught constraint violation aborts the entire
     * transaction (Postgres "current transaction is aborted"), which then fails Modulith's own
     * AFTER_COMMIT completion write and leaves the event stuck for redelivery. See
     * catalog.RelationDiscoveredListener#on -- mirrors scan.ScanJobRepository#insertIfAbsent.
     * <p>
     * Has no {@code @Transactional} of its own -- like every other {@code insertIfAbsent} in this
     * codebase, it relies on an already-transactional caller (see {@code ArtistSeedService
     * #addSeedIfNew}, which is {@code @Transactional} for exactly this reason).
     *
     * @return the number of rows this call actually inserted: {@code 1} if no (owner, name) row
     * existed yet and this call created it, {@code 0} if the {@code ON CONFLICT} fired because a
     * row already existed (including one a concurrent racing call just committed -- issue #133,
     * the caller's way of telling whether IT won or lost that race).
     * <p>
     * {@code normalizedName} is passed by the caller rather than derived here because this is a
     * native query -- {@code Artist}'s {@code @PrePersist} never runs for it (#176).
     */
    @Modifying
    @Query(value = """
            INSERT INTO artist (owner, name, normalized_name, source, status, discovered_via, note, created_at)
            VALUES (:owner, :name, :normalizedName, :source, :status, :discoveredVia, :note, :createdAt)
            ON CONFLICT (owner, name) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("owner") String owner,
                        @Param("name") String name,
                        @Param("normalizedName") String normalizedName,
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

    /** All of one group's rows, in the order the page renders them (issue #155). */
    List<Artist> findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
        String owner, ArtistStatus status, String discoveredVia, ArtistSource source);

    /**
     * The IS-NULL counterpart to
     * {@link #findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc}, for the "Ungrouped"
     * bucket (issue #156): {@code discoveredVia = 'Ungrouped'} (the sentinel string the
     * grouped-count query above maps a null {@code discoveredVia} to for display) can never match a
     * NULL column in SQL, so a row-fetch for that bucket needs an explicit {@code IS NULL} query
     * rather than reusing the exact-match method above with the sentinel as its parameter.
     * <p>
     * Ordered by name for exactly the reason its sibling is (issue #155): these rows feed the same
     * render AND the same focus-successor lookup, and "the next row" is undefined without a
     * deterministic order. An unordered Ungrouped bucket would render in whatever order Postgres
     * happened to return and name a successor the page cannot be relied on to reproduce.
     */
    List<Artist> findByOwnerAndStatusAndDiscoveredViaIsNullAndSourceOrderByNameAsc(
        String owner, ArtistStatus status, ArtistSource source);

    /** Total pending candidates for the owner -- the nav badge. */
    long countByOwnerAndStatus(String owner, ArtistStatus status);
}
