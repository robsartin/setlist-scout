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

import static com.robsartin.setlistscout.catalog.ArtistSource.MEMBER_EXPANSION;
import static com.robsartin.setlistscout.catalog.ArtistSource.SEED_LIST;
import static com.robsartin.setlistscout.catalog.ArtistSource.SIMILAR_EXPANSION;
import static com.robsartin.setlistscout.catalog.ArtistSource.TRIBUTE_EXPANSION;
import static com.robsartin.setlistscout.catalog.ArtistStatus.APPROVED;
import static com.robsartin.setlistscout.catalog.ArtistStatus.PENDING_REVIEW;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Testcontainers-backed round-trip of the candidate-grouping queries on ArtistRepository --
 * the data layer for the new grouped Candidates page (issue #96 PR2). Boots the full context
 * (like ScanJobRepositoryTest / PollerFlowTest) rather than an @ApplicationModuleTest slice, for
 * consistency with this suite's other Testcontainers-backed repository tests.
 */
@SpringBootTest
@Testcontainers
class CandidateQueryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "candidate-query-test@example.com";
    private static final String OTHER_OWNER = "someone@else.com";

    @Autowired
    private ArtistRepository artistRepository;

    @BeforeEach
    void clearArtists() {
        artistRepository.deleteAll();
    }

    @Test
    @DisplayName("groups PENDING_REVIEW candidates by base artist (discoveredVia) and source, "
            + "scoped to the owner, and supports a full-group fetch + total pending count")
    void groupsPendingCandidatesByBaseArtistAndSource() {
        save("Mike Campbell", MEMBER_EXPANSION, PENDING_REVIEW, "Tom Petty", OWNER);
        save("Benmont Tench", MEMBER_EXPANSION, PENDING_REVIEW, "Tom Petty", OWNER);
        save("The Wallflowers", SIMILAR_EXPANSION, PENDING_REVIEW, "Tom Petty", OWNER);
        save("Some Tribute", TRIBUTE_EXPANSION, PENDING_REVIEW, "Tom Petty", OWNER);
        save("Nels Cline", MEMBER_EXPANSION, PENDING_REVIEW, "Wilco", OWNER);
        save("Already In", MEMBER_EXPANSION, APPROVED, "Tom Petty", OWNER); // excluded (not pending)
        save("Other Owner", MEMBER_EXPANSION, PENDING_REVIEW, "Tom Petty", OTHER_OWNER); // excluded (owner)

        List<CandidateGroupCount> groups =
                artistRepository.countByStatusGroupedByViaAndSource(OWNER, PENDING_REVIEW);
        // Tom Petty: Members 2, Similar 1, Tributes 1 ; Wilco: Members 1  => 4 groups
        assertThat(groups).hasSize(4);
        assertThat(groups).anySatisfy(g -> {
            assertThat(g.getVia()).isEqualTo("Tom Petty");
            assertThat(g.getSource()).isEqualTo(MEMBER_EXPANSION);
            assertThat(g.getCount()).isEqualTo(2);
        });

        assertThat(artistRepository.countByOwnerAndStatus(OWNER, PENDING_REVIEW)).isEqualTo(5);

        List<Artist> all = artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                OWNER, PENDING_REVIEW, "Tom Petty", MEMBER_EXPANSION);
        assertThat(all).hasSize(2);
    }

    @Test
    @DisplayName("issue #156: a null discoveredVia (the Ungrouped bucket) is fetched via an explicit "
            + "IS NULL query -- discoveredVia = 'Ungrouped' can never match a NULL column in SQL -- "
            + "and comes back name-ordered, like every other bucket (issue #155)")
    void findsRowsWithNullDiscoveredViaViaExplicitIsNullQuery() {
        // A SEED artist's discoveredVia is null by construction (only expansion-discovery sets it) --
        // the actually-reachable combination (issue #156), not an arbitrary synthetic one.
        // Saved deliberately out of order: insertion order is Zeta, Direct, Alpha.
        save("Zeta Seed Artist", SEED_LIST, PENDING_REVIEW, null, OWNER);
        save("Direct Seed Artist", SEED_LIST, PENDING_REVIEW, null, OWNER);
        save("Alpha Seed Artist", SEED_LIST, PENDING_REVIEW, null, OWNER);
        save("Has A Via", MEMBER_EXPANSION, PENDING_REVIEW, "Tom Petty", OWNER);
        save("Other Owner Null Via", SEED_LIST, PENDING_REVIEW, null, OTHER_OWNER);

        List<Artist> found = artistRepository.findByOwnerAndStatusAndDiscoveredViaIsNullAndSourceOrderByNameAsc(
                OWNER, PENDING_REVIEW, SEED_LIST);

        // containsExactly is order-sensitive on purpose, so this pins three things at once: the
        // IS NULL filter, the owner scoping, and the A-Z order. That order is not cosmetic -- the
        // Ungrouped bucket feeds the same ActionOutcome.afterRow successor lookup as every other
        // bucket (issue #155), and "the next row" is undefined without it.
        assertThat(found).extracting(Artist::getName)
                .containsExactly("Alpha Seed Artist", "Direct Seed Artist", "Zeta Seed Artist");
    }

    private Artist save(String name, ArtistSource source, ArtistStatus status, String discoveredVia, String owner) {
        Artist artist = new Artist(name, source, status, discoveredVia, null);
        artist.setOwner(owner);
        return artistRepository.save(artist);
    }
}
