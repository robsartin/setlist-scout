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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Testcontainers-backed proof of {@link ArtistRepository#insertIfAbsent}'s {@code ON CONFLICT
 * (owner, name) DO NOTHING} semantics against real Postgres -- originally the D1 fix (#95) for
 * {@code CandidatePersistenceListener}'s tx-poisoning bug; that listener was replaced by
 * {@code RelationDiscoveredListener} in #109, which relies on this same guard and (unlike its
 * predecessor) no longer has an app-layer {@code existsByOwnerAndNameIgnoreCase} pre-check in
 * front of it -- every to-artist upsert, including a repeat one for an already-known artist,
 * reaches this method, so the DB-level guard tested here is now the ONLY thing absorbing a
 * duplicate (owner, name) insert, not just a race-only backstop. Testing the repository method
 * directly against a real, already-committed conflicting row proves the DB-level guard --
 * matching the {@code artist_owner_name_key} unique constraint from {@code V1__baseline.sql} --
 * absorbs a duplicate insert without throwing or double-writing, exactly like
 * {@code scan.ScanJobRepository#insertIfAbsent}.
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
    @DisplayName("the (owner, name) unique constraint is case-sensitive at the DB level")
    @Transactional
    void uniqueConstraintIsCaseSensitive() {
        artistRepository.insertIfAbsent(OWNER, "Radiohead", ArtistSource.SEED_LIST.name(),
                ArtistStatus.PENDING_REVIEW.name(), null, null, Instant.now());
        artistRepository.insertIfAbsent(OWNER, "radiohead", ArtistSource.SEED_LIST.name(),
                ArtistStatus.PENDING_REVIEW.name(), null, null, Instant.now());

        List<Artist> all = artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW);
        assertThat(all).as("case-variant names are distinct rows at the DB constraint level "
                + "(the case-insensitive dedup is a best-effort application-layer pre-check only)")
                .hasSize(2);
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
}
