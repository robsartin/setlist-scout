package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Testcontainers-backed proof of {@link ArtistRepository#insertIfAbsent}'s {@code ON CONFLICT
 * (owner, normalized_name) DO NOTHING} semantics against real Postgres -- originally the D1 fix
 * (#95) for {@code CandidatePersistenceListener}'s tx-poisoning bug; that listener was replaced by
 * {@code RelationDiscoveredListener} in #109, which relies on this same guard, so every to-artist
 * upsert -- including a repeat one for an already-known artist -- reaches this method and the
 * DB-level guard tested here is the ONLY thing absorbing a duplicate insert, not just a race-only
 * backstop. Testing the repository method directly against a real, already-committed conflicting
 * row proves that guard absorbs a duplicate without throwing or double-writing, exactly like
 * {@code scan.ScanJobRepository#insertIfAbsent}.
 * <p>
 * The conflict target moved from {@code (owner, name)} to {@code (owner, normalized_name)} in #179,
 * once {@code V21__unique_artist_normalized_name} added that constraint. What that changes is
 * which collisions get absorbed: a SPELLING variant of an existing row used to sail past the
 * arbiter and become a second row (issue #118's whole complaint), and is now absorbed by the
 * database itself. {@link #caseVariantIsAbsorbedByTheNormalizedNameConstraint()} and
 * {@link #punctuationVariantIsAbsorbedByTheNormalizedNameConstraint()} pin exactly that.
 */
@SpringBootTest
@Testcontainers
class ArtistRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "artist-repo-test@example.com";

    @Autowired
    private ArtistRepository artistRepository;

    @BeforeEach
    void clearArtists() {
        artistRepository.deleteAll();
    }

    @Test
    @DisplayName("insertIfAbsent creates a new PENDING_REVIEW artist row when none exists")
    // insertIfAbsent is a @Modifying native query with no @Transactional of its own (it's only
    // ever called from an already-transactional caller in production -- CandidatePersistenceListener's
    // @ApplicationModuleListener -- see ArtistRepository's Javadoc), so calling it directly here
    // needs an ambient transaction supplied by the test, same as ScanJobRepositoryTest's
    // redueAllResetsJobsAndBumpsVersion does for the identical reason.
    @Transactional
    void insertIfAbsentCreatesRowWhenAbsent() {
        artistRepository.insertIfAbsent(OWNER, "Discovered Candidate Band",
                ArtistNameNormalizer.normalize("Discovered Candidate Band"),
                ArtistSource.SIMILAR_EXPANSION.name(), ArtistStatus.PENDING_REVIEW.name(),
                "Base Artist", "similar to Base Artist", Instant.now());

        List<Artist> found = artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW);
        assertThat(found).hasSize(1);
        Artist artist = found.get(0);
        assertThat(artist.getName()).isEqualTo("Discovered Candidate Band");
        assertThat(artist.getSource()).isEqualTo(ArtistSource.SIMILAR_EXPANSION);
        assertThat(artist.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(artist.getDiscoveredVia()).isEqualTo("Base Artist");
        assertThat(artist.getNote()).isEqualTo("similar to Base Artist");
    }

    @Test
    @DisplayName("insertIfAbsent against a pre-existing (owner, name) row is a silent no-op: no "
            + "exception, and no duplicate row -- the ON CONFLICT DO NOTHING guard the #95 D1 fix relies on")
    @Transactional
    void insertIfAbsentIsIdempotentAgainstAPreExistingConflictingRow() {
        Artist preExisting = new Artist("Discovered Candidate Band", ArtistSource.MEMBER_EXPANSION,
                ArtistStatus.PENDING_REVIEW, "Original Discovery Path", "original note");
        preExisting.setOwner(OWNER);
        artistRepository.saveAndFlush(preExisting);

        // A second, differently-classified discovery of the exact same (owner, name) -- the real
        // race shape: two concurrent RelationDiscovered deliveries for the same candidate, one
        // of which already committed by the time this one's insert runs.
        assertThatCode(() -> artistRepository.insertIfAbsent(OWNER, "Discovered Candidate Band",
                ArtistNameNormalizer.normalize("Discovered Candidate Band"),
                ArtistSource.SIMILAR_EXPANSION.name(), ArtistStatus.PENDING_REVIEW.name(),
                "Different Discovery Path", "different note", Instant.now()))
                .as("ON CONFLICT DO NOTHING never throws, unlike a raw save() against the unique constraint")
                .doesNotThrowAnyException();

        List<Artist> matching = artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW).stream()
                .filter(a -> "Discovered Candidate Band".equals(a.getName()))
                .toList();
        assertThat(matching).as("no duplicate row").hasSize(1);
        assertThat(matching.get(0).getDiscoveredVia())
                .as("DO NOTHING means the original row wins, not the racing insert's values")
                .isEqualTo("Original Discovery Path");
    }

    @Test
    @DisplayName("issue #179: a case variant of an existing row is absorbed by the DB, not stored as "
            + "a second row -- this replaces the pre-#179 behaviour, where case-variant names were "
            + "distinct rows and only a best-effort application pre-check stood between them")
    @Transactional
    void caseVariantIsAbsorbedByTheNormalizedNameConstraint() {
        artistRepository.insertIfAbsent(OWNER, "Radiohead", ArtistNameNormalizer.normalize("Radiohead"),
                ArtistSource.SEED_LIST.name(), ArtistStatus.PENDING_REVIEW.name(), null, null, Instant.now());

        int inserted = artistRepository.insertIfAbsent(OWNER, "radiohead",
                ArtistNameNormalizer.normalize("radiohead"), ArtistSource.SEED_LIST.name(),
                ArtistStatus.PENDING_REVIEW.name(), null, null, Instant.now());

        assertThat(inserted).as("the case variant conflicted rather than inserting").isZero();
        List<Artist> all = artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW);
        assertThat(all).as("one row, under the original spelling -- UNIQUE (owner, normalized_name) "
                + "makes the case-insensitive dedup a database guarantee, not a pre-check")
                .hasSize(1);
        assertThat(all.get(0).getName()).isEqualTo("Radiohead");
    }

    @Test
    @DisplayName("issue #179: a punctuation/whitespace variant is absorbed too -- the exact shape "
            + "(#157 hyphen spacing) that produced the last duplicate pair left in production, and "
            + "the one the old ON CONFLICT (owner, name) target could never have caught")
    @Transactional
    void punctuationVariantIsAbsorbedByTheNormalizedNameConstraint() {
        String original = "Paul Quinichette-John Coltrane Quintet";
        String variant = "Paul Quinichette - John Coltrane Quintet";
        artistRepository.insertIfAbsent(OWNER, original, ArtistNameNormalizer.normalize(original),
                ArtistSource.SEED_LIST.name(), ArtistStatus.REJECTED.name(), null, null, Instant.now());

        int inserted = artistRepository.insertIfAbsent(OWNER, variant, ArtistNameNormalizer.normalize(variant),
                ArtistSource.SIMILAR_EXPANSION.name(), ArtistStatus.PENDING_REVIEW.name(), null, null,
                Instant.now());

        assertThat(inserted).as("the spelling variant conflicted rather than inserting").isZero();
        assertThat(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .as("a rejected artist cannot reappear as a PENDING_REVIEW candidate under a new "
                        + "spelling -- issue #118, now enforced by the database")
                .isEmpty();
        assertThat(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.REJECTED)).hasSize(1);
    }

    @Test
    @DisplayName("issue #179: (owner, name) uniqueness is deliberately kept alongside the stronger "
            + "constraint -- a different owner may still hold the same name, which is what makes it "
            + "the composite constraint and not a single-column one")
    @Transactional
    void theSameNameUnderADifferentOwnerIsStillItsOwnRow() {
        String name = "Radiohead";
        artistRepository.insertIfAbsent(OWNER, name, ArtistNameNormalizer.normalize(name),
                ArtistSource.SEED_LIST.name(), ArtistStatus.PENDING_REVIEW.name(), null, null, Instant.now());

        int inserted = artistRepository.insertIfAbsent("someone-else@example.com", name,
                ArtistNameNormalizer.normalize(name), ArtistSource.SEED_LIST.name(),
                ArtistStatus.PENDING_REVIEW.name(), null, null, Instant.now());

        assertThat(inserted).as("owner-scoped: another owner's identical name is a new row").isEqualTo(1);
        assertThat(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).hasSize(1);
        assertThat(artistRepository.findByOwnerAndStatus("someone-else@example.com",
                ArtistStatus.PENDING_REVIEW)).hasSize(1);
    }

    @Test
    @DisplayName("saving an Artist with status REMOVED does not violate artist_status_check")
    @Transactional
    void savingAnArtistWithRemovedStatusDoesNotViolateTheStatusCheckConstraint() {
        Artist artist = new Artist("Formerly Seeded Band", ArtistSource.SEED_LIST, ArtistStatus.REMOVED, null, null);
        artist.setOwner(OWNER);

        assertThatCode(() -> artistRepository.saveAndFlush(artist))
                .as("proves the migration widened artist_status_check to allow REMOVED")
                .doesNotThrowAnyException();

        List<Artist> found = artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.REMOVED);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getName()).isEqualTo("Formerly Seeded Band");
    }

    // --- Issue #174: keyset (seek) pagination on normalizedName ----------------------------------

    private static final List<ArtistStatus> KEYSET_ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    private Artist saveActive(String owner, String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist);
    }

    @Test
    @DisplayName("issue #174: a page returns exactly the page size, and the next cursor's page picks "
            + "up immediately after it -- concatenating pages covers every row exactly once, no "
            + "overlap and no gap")
    void keysetFirstAndNextPagesCoverEveryRowExactlyOnceNoOverlapNoGap() {
        List<String> names = List.of("Aardvark", "Bobcat", "Cheetah", "Dingo", "Eagle", "Falcon",
                "Gecko", "Heron", "Ibis", "Jackal");
        names.forEach(n -> saveActive(OWNER, n));

        List<Artist> page1 = artistRepository.findActiveFirstPage(OWNER, KEYSET_ACTIVE, 4);
        assertThat(page1).as("exact page size").hasSize(4);
        assertThat(page1).extracting(Artist::getName).containsExactly("Aardvark", "Bobcat", "Cheetah", "Dingo");

        List<Artist> page2 = artistRepository.findActiveAfter(OWNER, KEYSET_ACTIVE,
                page1.get(page1.size() - 1).getNormalizedName(), 4);
        assertThat(page2).extracting(Artist::getName).containsExactly("Eagle", "Falcon", "Gecko", "Heron");

        List<Artist> page3 = artistRepository.findActiveAfter(OWNER, KEYSET_ACTIVE,
                page2.get(page2.size() - 1).getNormalizedName(), 4);
        assertThat(page3).as("final, partial page").extracting(Artist::getName).containsExactly("Ibis", "Jackal");

        List<String> allPaged = new ArrayList<>();
        List.of(page1, page2, page3).forEach(page -> page.forEach(a -> allPaged.add(a.getName())));
        assertThat(allPaged).as("no overlap and no gap: every seeded name appears exactly once, in order")
                .containsExactlyElementsOf(names);
    }

    @Test
    @DisplayName("issue #174: mixed-case names page correctly across a cursor boundary -- "
            + "normalizedName is both the ORDER BY expression and the cursor comparison, so a raw "
            + "case-sensitive byte order (every capitalized name before any lowercase one) can never "
            + "disagree with the normalized order this test expects")
    void keysetPagesMixedCaseNamesCorrectlyAcrossACursorBoundary() {
        // Case-SENSITIVE ASCII order would be APPLE, Cherry, Date Palm, banana, zebra -- every
        // capital-letter name sorts before every lowercase one. That is NOT the order this test
        // asserts, so a regression to comparing raw `name` while sorting by normalizedName (or vice
        // versa) fails this loudly rather than silently.
        saveActive(OWNER, "zebra");
        saveActive(OWNER, "Cherry");
        saveActive(OWNER, "APPLE");
        saveActive(OWNER, "banana");
        saveActive(OWNER, "Date Palm");

        List<Artist> page1 = artistRepository.findActiveFirstPage(OWNER, KEYSET_ACTIVE, 2);
        assertThat(page1).extracting(Artist::getName).containsExactly("APPLE", "banana");

        List<Artist> page2 = artistRepository.findActiveAfter(OWNER, KEYSET_ACTIVE,
                page1.get(1).getNormalizedName(), 2);
        assertThat(page2).extracting(Artist::getName).containsExactly("Cherry", "Date Palm");

        List<Artist> page3 = artistRepository.findActiveAfter(OWNER, KEYSET_ACTIVE,
                page2.get(1).getNormalizedName(), 2);
        assertThat(page3).extracting(Artist::getName).containsExactly("zebra");
    }

    @Test
    @DisplayName("issue #174: a row inserted BEFORE the current cursor -- even one that sorts inside "
            + "the range already shown -- does not duplicate or skip rows on the next page. This is "
            + "exactly the failure mode OFFSET pagination has: the boundary here is a VALUE "
            + "(normalizedName), not a row count, so an insert elsewhere can't shift it")
    void insertingARowBeforeTheCursorDoesNotDuplicateOrSkipTheNextPage() {
        List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot").forEach(n -> saveActive(OWNER, n));

        List<Artist> page1 = artistRepository.findActiveFirstPage(OWNER, KEYSET_ACTIVE, 3);
        assertThat(page1).extracting(Artist::getName).containsExactly("Alpha", "Bravo", "Charlie");
        String cursor = page1.get(2).getNormalizedName();

        // Sorts between Bravo and Charlie -- squarely inside the range page1 already rendered, and
        // strictly before "cursor". An OFFSET-based `LIMIT 3 OFFSET 3` would now return this row
        // again on "page 2" (everything after it shifts down one slot); keyset must not.
        saveActive(OWNER, "Beta");

        List<Artist> page2 = artistRepository.findActiveAfter(OWNER, KEYSET_ACTIVE, cursor, 3);
        assertThat(page2).extracting(Artist::getName)
                .as("unaffected by the insert before the cursor -- no duplicate, no skip")
                .containsExactly("Delta", "Echo", "Foxtrot");
    }

    @Test
    @DisplayName("issue #174: findActiveBefore, reversed for display, returns exactly the prior page")
    void previousPageReturnsExactlyThePriorPage() {
        List.of("Alpha", "Bravo", "Charlie", "Delta", "Echo", "Foxtrot").forEach(n -> saveActive(OWNER, n));

        List<Artist> page1 = artistRepository.findActiveFirstPage(OWNER, KEYSET_ACTIVE, 3);
        List<Artist> page2 = artistRepository.findActiveAfter(OWNER, KEYSET_ACTIVE,
                page1.get(2).getNormalizedName(), 3);
        assertThat(page2).extracting(Artist::getName).containsExactly("Delta", "Echo", "Foxtrot");

        List<Artist> previousDescending = artistRepository.findActiveBefore(OWNER, KEYSET_ACTIVE,
                page2.get(0).getNormalizedName(), 3);
        assertThat(previousDescending).as("DESC order, nearest the cursor first")
                .extracting(Artist::getName).containsExactly("Charlie", "Bravo", "Alpha");

        List<Artist> previousAscending = new ArrayList<>(previousDescending);
        Collections.reverse(previousAscending);
        assertThat(previousAscending).as("reversed for display: exactly page1")
                .extracting(Artist::getName).containsExactly("Alpha", "Bravo", "Charlie");
    }

    @Test
    @DisplayName("issue #174: keyset pagination stays owner-scoped -- a second owner's artists never appear")
    void keysetPaginationIsOwnerScoped() {
        saveActive(OWNER, "Owner One Band");
        saveActive("someone-else@example.com", "Owner Two Band");

        List<Artist> page1 = artistRepository.findActiveFirstPage(OWNER, KEYSET_ACTIVE, 10);

        assertThat(page1).extracting(Artist::getName).containsExactly("Owner One Band");
    }
}
