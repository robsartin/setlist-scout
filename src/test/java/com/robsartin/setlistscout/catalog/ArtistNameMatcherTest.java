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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed proof that {@link ArtistNameMatcher#findExistingMatch} finds the same
 * matches it did back when it scanned the owner's whole catalog and re-normalized every row in
 * Java, now that it performs one indexed {@code findFirstByOwnerAndNormalizedName} lookup instead
 * (#176). Seeds real rows through {@link ArtistRepository#saveAndFlush}, which populates {@code
 * normalized_name} via {@code Artist}'s {@code @PrePersist} (see that class's Javadoc) -- there is
 * no repository mock left to hand-feed a canned "existing artists" list, so every case here proves
 * the real {@code (owner, normalized_name)} index does what the old Java-side scan-and-filter did.
 * <p>
 * That includes {@link #doesNotMatchAcrossOwners}, which a mocked {@code ArtistRepository} could
 * never have proven: the old mock returned whatever list it was told to return, regardless of
 * which owner the matcher actually passed through to the repository call, so a future regression
 * that dropped the owner filter from the query would have sailed through the old test suite
 * unnoticed. Only a real query against real rows for two different owners can catch that.
 */
@SpringBootTest
@Testcontainers
class ArtistNameMatcherTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rob@example.com";
    private static final String OTHER_OWNER = "someone-else@example.com";

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ArtistNameMatcher matcher;

    @BeforeEach
    void clearArtists() {
        artistRepository.deleteAll();
    }

    private Artist seed(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        return artistRepository.saveAndFlush(artist);
    }

    @Test
    @DisplayName("finds an exact-name match")
    void findsExactMatch() {
        Artist existing = seed(OWNER, "Radiohead", ArtistStatus.APPROVED);

        Optional<ArtistNameStatusView> found = matcher.findExistingMatch(OWNER, "Radiohead");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("finds an existing artist whose name differs only by case")
    void findsCaseVariant() {
        Artist existing = seed(OWNER, "Wilco", ArtistStatus.APPROVED);

        Optional<ArtistNameStatusView> found = matcher.findExistingMatch(OWNER, "wilco");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("finds an existing artist whose name differs by en-dash vs hyphen")
    void findsUnicodeEnDashVariant() {
        Artist existing = seed(OWNER, "Only Murders In The Building - Cast", ArtistStatus.REJECTED);

        Optional<ArtistNameStatusView> found =
                matcher.findExistingMatch(OWNER, "Only Murders in the Building – Cast");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("issue #157: finds a REJECTED artist whose name differs only by spacing around a "
            + "hyphen -- the real production pair, proving the reappearance guard still catches it "
            + "now that the lookup is indexed")
    void findsHyphenSpacingVariant() {
        Artist existing = seed(OWNER, "Paul Quinichette - John Coltrane Quintet", ArtistStatus.REJECTED);

        Optional<ArtistNameStatusView> found =
                matcher.findExistingMatch(OWNER, "Paul Quinichette-John Coltrane Quintet");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("finds an existing artist whose name differs only by curly vs straight apostrophe")
    void findsCurlyApostropheVariant() {
        Artist existing = seed(OWNER, "Charlie Parker's Re-Boppers", ArtistStatus.REJECTED);

        Optional<ArtistNameStatusView> found =
                matcher.findExistingMatch(OWNER, "Charlie Parker’s Re-Boppers");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(existing.getId());
    }

    @Test
    @DisplayName("two genuinely different names do not match")
    void distinctNamesDoNotMatch() {
        seed(OWNER, "Radiohead", ArtistStatus.APPROVED);

        assertThat(matcher.findExistingMatch(OWNER, "Radioheads")).isEmpty();
    }

    @Test
    @DisplayName("returns empty when the owner has no artists at all")
    void emptyWhenNoArtists() {
        assertThat(matcher.findExistingMatch(OWNER, "Wilco")).isEmpty();
    }

    @Test
    @DisplayName("owner-scoping: another owner's identically-named artist is not returned -- unprovable "
            + "against the old mocked repository, which returned whatever it was told regardless of "
            + "which owner was actually passed through")
    void doesNotMatchAcrossOwners() {
        seed(OTHER_OWNER, "Wilco", ArtistStatus.APPROVED);
        Artist ownersOwn = seed(OWNER, "Wilco", ArtistStatus.APPROVED);

        // Both owners have an identically-named row, so a present-but-wrong-owner result (the
        // owner filter silently dropped) and a genuinely-empty result (a broken query) would both
        // look like a pass if this only asserted emptiness. Asserting the match is OWNER's own row
        // rules out both failure modes at once.
        Optional<ArtistNameStatusView> found = matcher.findExistingMatch(OWNER, "Wilco");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(ownersOwn.getId());
    }
}
