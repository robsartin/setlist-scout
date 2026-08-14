package com.robsartin.setlistscout.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
 * (owner, name) DO NOTHING} semantics against real Postgres -- the D1 fix (#95) for
 * {@code CandidatePersistenceListener}'s tx-poisoning bug. This is deliberately a
 * repository-level test rather than a full listener/event-flow one: the listener's own
 * case-insensitive {@code existsByOwnerAndNameIgnoreCase} pre-check would absorb any
 * exact-or-case-variant pre-existing duplicate before ever reaching {@code insertIfAbsent}, so a
 * full-flow test with a pre-existing row can't actually exercise the ON CONFLICT branch (that
 * only fires on a genuine check-then-insert race between two concurrent deliveries). Testing the
 * repository method directly against a real, already-committed conflicting row is what actually
 * proves the DB-level guard -- matching the {@code artist_owner_name_key} unique constraint from
 * {@code V1__baseline.sql} -- absorbs a duplicate insert without throwing or double-writing,
 * exactly like {@code scan.ScanJobRepository#insertIfAbsent}.
 */
@SpringBootTest
@Testcontainers
class ArtistRepositoryTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-client-secret");
    }

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
        // race shape: two concurrent CandidateDiscovered deliveries for the same candidate, one
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
}
