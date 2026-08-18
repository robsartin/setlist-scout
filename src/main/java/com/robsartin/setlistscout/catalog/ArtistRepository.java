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
     * the {@code artist_owner_normalized_name_key} unique constraint. Deliberately status-unfiltered,
     * same as the scan it replaces: a normalized-name match against a REJECTED row is exactly the
     * case {@link ArtistNameMatcher} must catch (a rejected artist reappearing under a new spelling).
     * <p>
     * A genuine single-result lookup (#179), no longer the {@code findFirst} #176 had to settle for.
     * #176 shipped the column with a NON-unique index, so a pre-existing duplicate variant would
     * have made a unique lookup throw {@code IncorrectResultSizeDataAccessException} -- and inside
     * an {@code @ApplicationModuleListener} that poisons the whole listener transaction (ADR-0024).
     * {@code V21__unique_artist_normalized_name} merged the last duplicates and added
     * {@code UNIQUE (owner, normalized_name)}, so more than one row is now impossible rather than
     * merely unlikely, and "which row does this return" stops being unspecified.
     */
    Optional<ArtistNameStatusView> findByOwnerAndNormalizedName(String owner, String normalizedName);

    /**
     * Case-SENSITIVE exact-match lookup, used to resolve the row an {@link #insertIfAbsent} call
     * just CREATED -- that row carries the exact {@code name} string the caller passed, so this
     * resolves it precisely. Not usable to resolve a row that call merely CONFLICTED against: since
     * #179 the conflict target is {@code (owner, normalized_name)}, so the pre-existing row can be
     * a different spelling and this lookup would return empty. Use
     * {@link #findByOwnerAndNormalizedName} for that case (see {@code ArtistSeedService
     * #addSeedIfNew} and {@code RelationDiscoveredListener#resolveOrCreateToArtist}, which do
     * exactly that).
     */
    Optional<Artist> findByOwnerAndName(String owner, String name);

    /**
     * DB-level idempotent enqueue: relies on the {@code artist_owner_normalized_name_key} unique
     * constraint via {@code ON CONFLICT ... DO NOTHING} so a racing duplicate candidate for the
     * same artist is a silent no-op instead of a {@code DataIntegrityViolationException}. That
     * matters because {@code @ApplicationModuleListener} runs the whole listener body in one
     * transaction on an IDENTITY-keyed table: an uncaught constraint violation aborts the entire
     * transaction (Postgres "current transaction is aborted"), which then fails Modulith's own
     * AFTER_COMMIT completion write and leaves the event stuck for redelivery. See
     * catalog.RelationDiscoveredListener#on -- mirrors scan.ScanJobRepository#insertIfAbsent.
     *
     * <h2>Why the conflict target is (owner, normalized_name), not (owner, name) -- #179</h2>
     * It HAS to be, now that {@code V21__unique_artist_normalized_name} added
     * {@code UNIQUE (owner, normalized_name)}. {@code ON CONFLICT} suppresses violations only of
     * the constraint it names: targeting the narrower {@code (owner, name)} would let a
     * different-SPELLING duplicate ("Foo - Bar" against an existing "Foo-Bar") sail past the
     * arbiter and then throw on the normalized-name constraint instead -- reintroducing exactly
     * the tx-poisoning failure this method exists to prevent, on the very path #179 hardened.
     * The wider target also absorbs strictly more: a same-spelling repeat conflicts on BOTH
     * constraints, and the normalized arbiter fires first, so nothing that used to be absorbed
     * stops being absorbed.
     *
     * <h2>Why (owner, name) uniqueness is kept anyway -- #179</h2>
     * It is now redundant in normal operation and deliberately left in place. {@code normalize} is
     * a function of {@code name}, so two rows sharing {@code (owner, name)} necessarily share
     * {@code (owner, normalized_name)} -- meaning the normalized constraint strictly implies this
     * one and this one can never fire on its own. That is precisely what makes it worth keeping:
     * the only way to violate it is to write a {@code normalized_name} that is NOT the normalizer's
     * output for its own {@code name} (a hand-written SQL insert, or a future call site passing a
     * mismatched value to this method), which is a data-integrity bug worth failing on rather than
     * absorbing. It also keeps {@link #findByOwnerAndName}'s single-result contract an explicit
     * database guarantee instead of one derived from the normalizer's behaviour.
     * <p>
     * Has no {@code @Transactional} of its own -- like every other {@code insertIfAbsent} in this
     * codebase, it relies on an already-transactional caller (see {@code ArtistSeedService
     * #addSeedIfNew}, which is {@code @Transactional} for exactly this reason).
     *
     * @return the number of rows this call actually inserted: {@code 1} if no row for this owner
     * and normalized name existed yet and this call created it, {@code 0} if the
     * {@code ON CONFLICT} fired because one already existed (including one a concurrent racing call
     * just committed -- issue #133, the caller's way of telling whether IT won or lost that race).
     * <p>
     * {@code normalizedName} is passed by the caller rather than derived here because this is a
     * native query -- {@code Artist}'s {@code @PrePersist} never runs for it (#176).
     */
    @Modifying
    @Query(value = """
            INSERT INTO artist (owner, name, normalized_name, source, status, discovered_via, note, created_at)
            VALUES (:owner, :name, :normalizedName, :source, :status, :discoveredVia, :note, :createdAt)
            ON CONFLICT (owner, normalized_name) DO NOTHING
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
