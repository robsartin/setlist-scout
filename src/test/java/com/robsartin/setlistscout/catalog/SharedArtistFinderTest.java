package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #163. This class exists because an exact-name join looks correct and under-reports badly against
 * real data -- see {@link #normalizationIsWhatMakesTheIntersectionWork}.
 */
@SpringBootTest
@Testcontainers
class SharedArtistFinderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";
    private static final String DAVID = "david@example.com";
    private static final String STRANGER = "stranger@example.com";
    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    @Autowired private SharedArtistFinder finder;
    @Autowired private ArtistRepository artistRepository;

    @BeforeEach
    void clean() {
        artistRepository.deleteAll();
    }

    private void seed(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    @Test
    @DisplayName("matches two spellings that differ only by case -- the real production pair")
    void matchesCaseVariantNames() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).containsExactly("Tom Petty");
    }

    @Test
    @DisplayName("normalization is what makes the intersection work: exact match finds 1 of 4")
    void normalizationIsWhatMakesTheIntersectionWork() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);
        seed(ROB, "James Taylor", ArtistStatus.SEED);
        seed(DAVID, "James taylor", ArtistStatus.SEED);
        seed(ROB, "Bruce Springsteen", ArtistStatus.SEED);
        seed(DAVID, "Bruce springsteen", ArtistStatus.SEED);
        seed(ROB, "Brandi Carlile", ArtistStatus.SEED);
        seed(DAVID, "Brandi Carlile", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID))
                .containsExactly("Brandi Carlile", "Bruce Springsteen", "James Taylor", "Tom Petty");

        // The regression guard. An exact-name join over this same data finds only the single
        // identically-spelled pair. If findSharedArtistNames is ever "simplified" to string
        // equality, the assertion above drops to 1 -- this documents why that is wrong.
        List<String> davidNames = artistRepository.findByOwnerAndStatusIn(DAVID, ACTIVE)
                .stream().map(Artist::getName).toList();
        long exactMatches = artistRepository.findByOwnerAndStatusIn(ROB, ACTIVE)
                .stream().filter(a -> davidNames.contains(a.getName())).count();
        assertThat(exactMatches).isEqualTo(1);
    }

    @Test
    @DisplayName("only SEED and APPROVED are active on either side")
    void onlyActiveStatusesIntersect() {
        seed(ROB, "Approved Both", ArtistStatus.APPROVED);
        seed(DAVID, "Approved Both", ArtistStatus.SEED);
        seed(ROB, "Rejected By David", ArtistStatus.SEED);
        seed(DAVID, "Rejected By David", ArtistStatus.REJECTED);
        seed(ROB, "Removed By Rob", ArtistStatus.REMOVED);
        seed(DAVID, "Removed By Rob", ArtistStatus.SEED);
        seed(ROB, "Pending For David", ArtistStatus.SEED);
        seed(DAVID, "Pending For David", ArtistStatus.PENDING_REVIEW);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).containsExactly("Approved Both");
    }

    @Test
    @DisplayName("a third owner's artists never leak into the intersection")
    void ownerIsolation() {
        seed(ROB, "Shared", ArtistStatus.SEED);
        seed(DAVID, "Shared", ArtistStatus.SEED);
        seed(STRANGER, "Stranger Only", ArtistStatus.SEED);
        seed(ROB, "Stranger Only", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).containsExactly("Shared");
    }

    @Test
    @DisplayName("no overlap returns empty, not an error")
    void noOverlapIsEmpty() {
        seed(ROB, "Only Rob", ArtistStatus.SEED);
        seed(DAVID, "Only David", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).isEmpty();
    }

    @Test
    @DisplayName("results come back in ownerA's spelling, sorted case-insensitively")
    void returnsOwnerASpellingSorted() {
        seed(ROB, "zz top", ArtistStatus.SEED);
        seed(DAVID, "ZZ Top", ArtistStatus.SEED);
        seed(ROB, "ABBA", ArtistStatus.SEED);
        seed(DAVID, "abba", ArtistStatus.SEED);

        assertThat(finder.findSharedArtistNames(ROB, DAVID)).containsExactly("ABBA", "zz top");
    }
}
