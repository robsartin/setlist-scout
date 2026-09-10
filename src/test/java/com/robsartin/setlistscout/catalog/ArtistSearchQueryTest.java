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
 * Issue #250: the filtered keyset queries, against real Postgres. HQL's {@code LIKE ... ESCAPE}
 * and the wildcard escaping in {@link ArtistSearchTerm} only mean anything if the database agrees,
 * which a mock cannot tell us.
 */
@SpringBootTest
@Testcontainers
class ArtistSearchQueryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "artist-search-test@example.com";
    private static final String OTHER_OWNER = "someone-else@example.com";
    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    @Autowired
    private ArtistRepository artistRepository;

    @BeforeEach
    void setUp() {
        artistRepository.deleteAll();
        save(OWNER, "Tom Petty", ArtistStatus.APPROVED);
        save(OWNER, "Beyoncé", ArtistStatus.APPROVED);
        save(OWNER, "Foo-Bar", ArtistStatus.SEED);
        save(OWNER, "50% Off", ArtistStatus.SEED);
        save(OWNER, "5013 Off", ArtistStatus.SEED);
        save(OWNER, "Petty Officer", ArtistStatus.SEED);
        save(OWNER, "Amanda Shires", ArtistStatus.REJECTED);
        save(OTHER_OWNER, "Tom Petty", ArtistStatus.APPROVED);
    }

    @Test
    @DisplayName("a case-different query finds the artist")
    void foldsCase() {
        assertThat(names(search("tom petty"))).containsExactly("Tom Petty");
        assertThat(names(search("beyoncÉ"))).containsExactly("Beyoncé");
    }

    /**
     * Until #268 this test asserted {@code search("BEYONCE")} was EMPTY, commented "accents are not
     * stripped, by design". That was the design, and it meant anyone without an accented keyboard
     * could not reach the artist at all. This is the same assertion inverted, and it is the
     * end-to-end proof of #268's search half -- the normalizer folds the query, V36 backfills the
     * column to the same form, and the two meet.
     */
    @Test
    @DisplayName("issue #268: a query typed without accents finds the accented artist")
    void unaccentedQueryFindsAnAccentedArtist() {
        assertThat(names(search("BEYONCE"))).containsExactly("Beyoncé");
        assertThat(names(search("beyonce"))).containsExactly("Beyoncé");
    }

    @Test
    @DisplayName("a hyphen-spacing variant finds it -- 'foo - bar' matches 'Foo-Bar' (#157). This "
            + "is the assertion that proves the normalizer is used; a naive lower() passes the "
            + "case test above and fails this one.")
    void foldsHyphenSpacing() {
        assertThat(names(search("foo - bar"))).containsExactly("Foo-Bar");
    }

    @Test
    @DisplayName("a substring in the middle of a name matches")
    void matchesSubstring() {
        assertThat(names(search("petty"))).containsExactly("Petty Officer", "Tom Petty");
    }

    @Test
    @DisplayName("'%' in a query is a literal percent, not a wildcard -- '50%' must not match "
            + "'5013 Off'")
    void escapesWildcards() {
        assertThat(names(search("50%"))).containsExactly("50% Off");
    }

    @Test
    @DisplayName("search never returns another owner's artists")
    void ownerScoped() {
        assertThat(search("tom petty")).hasSize(1);
        assertThat(search("tom petty").get(0).getOwner()).isEqualTo(OWNER);
    }

    @Test
    @DisplayName("the active search excludes rejected artists; the inactive query finds them")
    void separatesActiveFromRejected() {
        assertThat(names(search("shires"))).isEmpty();
        assertThat(names(artistRepository.findInactiveMatching(OWNER,
                List.of(ArtistStatus.REJECTED, ArtistStatus.REMOVED),
                ArtistSearchTerm.likePattern("shires"), 20)))
                .containsExactly("Amanda Shires");
    }

    @Test
    @DisplayName("the keyset cursor still holds under a filter: after/before walk the matches only")
    void cursorsRespectTheFilter() {
        String pattern = ArtistSearchTerm.likePattern("petty");

        List<Artist> firstOne = artistRepository.findActiveMatchingFirstPage(OWNER, ACTIVE, pattern, 1);
        assertThat(names(firstOne)).containsExactly("Petty Officer");

        List<Artist> after = artistRepository.findActiveMatchingAfter(OWNER, ACTIVE, pattern,
                firstOne.get(0).getNormalizedName(), 10);
        assertThat(names(after)).containsExactly("Tom Petty");

        List<Artist> before = artistRepository.findActiveMatchingBefore(OWNER, ACTIVE, pattern,
                after.get(0).getNormalizedName(), 10);
        assertThat(names(before)).containsExactly("Petty Officer");
    }

    private List<Artist> search(String query) {
        return artistRepository.findActiveMatchingFirstPage(OWNER, ACTIVE,
                ArtistSearchTerm.likePattern(query), 50);
    }

    private static List<String> names(List<Artist> artists) {
        return artists.stream().map(Artist::getName).toList();
    }

    private void save(String owner, String name, ArtistStatus status) {
        Artist a = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        a.setOwner(owner);
        artistRepository.save(a);
    }
}
