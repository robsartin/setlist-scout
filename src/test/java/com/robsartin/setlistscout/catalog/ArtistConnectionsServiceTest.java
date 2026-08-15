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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * Testcontainers-backed proof of {@link ArtistConnectionsService#discoverConnections}, the
 * traversal/aggregation logic extracted for issue #112 (graph phase 3): a 2-hop traversal of the
 * {@code artist_edge} graph starting from EVERY one of the owner's active (SEED/APPROVED)
 * artists at once, surfacing PENDING_REVIEW artists reachable that way -- see the class Javadoc
 * on {@link ArtistConnectionsService} for why "already in the catalog" is read as "the owner has
 * already decided" (SEED/APPROVED/REJECTED/REMOVED) rather than "any Artist row exists at all".
 */
@SpringBootTest
@Testcontainers
class ArtistConnectionsServiceTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "connections-test@example.com";
    private static final String OTHER_OWNER = "connections-test-2@example.com";

    @Autowired
    private ArtistConnectionsService connectionsService;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ArtistEdgeRepository artistEdgeRepository;

    @BeforeEach
    void clearTables() {
        artistEdgeRepository.deleteAll();
        artistRepository.deleteAll();
    }

    private Long createArtist(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist).getId();
    }

    private void saveEdge(String owner, Long from, Long to, String type, String source) {
        artistEdgeRepository.save(new ArtistEdge(owner, from, to, type, source, null, null, Instant.now()));
    }

    @Test
    @DisplayName("a directly (1-hop) connected PENDING_REVIEW artist is discovered, with its seed and edge shown")
    void discoversOneHopConnection() {
        Long seed = createArtist(OWNER, "Radiohead", ArtistStatus.SEED);
        Long b = createArtist(OWNER, "Thom Yorke", ArtistStatus.PENDING_REVIEW);
        saveEdge(OWNER, seed, b, "MEMBER_OF", "musicbrainz");

        List<DiscoveredConnection> discovered = connectionsService.discoverConnections(OWNER);

        assertThat(discovered).extracting(DiscoveredConnection::artistId).containsExactly(b);
        DiscoveredConnection connection = discovered.get(0);
        assertThat(connection.artistName()).isEqualTo("Thom Yorke");
        assertThat(connection.paths()).containsExactly(
                new ConnectionPath("Radiohead", null, 1, "MEMBER_OF", "musicbrainz"));
    }

    @Test
    @DisplayName("a 2-hop connected PENDING_REVIEW artist (no direct edge from any active artist) is discovered "
            + "via its intermediate artist")
    void discoversTwoHopConnection() {
        Long seed = createArtist(OWNER, "Radiohead", ArtistStatus.SEED);
        Long b = createArtist(OWNER, "Thom Yorke", ArtistStatus.PENDING_REVIEW);
        Long c = createArtist(OWNER, "Atoms for Peace", ArtistStatus.PENDING_REVIEW);
        saveEdge(OWNER, seed, b, "MEMBER_OF", "musicbrainz");
        saveEdge(OWNER, b, c, "MEMBER_OF", "discogs");

        List<DiscoveredConnection> discovered = connectionsService.discoverConnections(OWNER);

        assertThat(discovered).extracting(DiscoveredConnection::artistId)
                .containsExactlyInAnyOrder(b, c);
        DiscoveredConnection viaConnection = discovered.stream()
                .filter(d -> d.artistId().equals(c)).findFirst().orElseThrow();
        assertThat(viaConnection.paths()).containsExactly(
                new ConnectionPath("Radiohead", "Thom Yorke", 2, "MEMBER_OF", "discogs"));
    }

    @Test
    @DisplayName("the 2-hop bound is respected: an artist reachable only at 3 hops does not appear")
    void respectsTwoHopBound() {
        Long seed = createArtist(OWNER, "A", ArtistStatus.SEED);
        Long b = createArtist(OWNER, "B", ArtistStatus.PENDING_REVIEW);
        Long c = createArtist(OWNER, "C", ArtistStatus.PENDING_REVIEW);
        Long d = createArtist(OWNER, "D", ArtistStatus.PENDING_REVIEW);
        saveEdge(OWNER, seed, b, "MEMBER_OF", "musicbrainz");
        saveEdge(OWNER, b, c, "MEMBER_OF", "musicbrainz");
        saveEdge(OWNER, c, d, "MEMBER_OF", "musicbrainz");

        List<DiscoveredConnection> discovered = connectionsService.discoverConnections(OWNER);

        assertThat(discovered).extracting(DiscoveredConnection::artistId)
                .as("B (1 hop) and C (2 hops) appear, D (3 hops only) does not")
                .containsExactlyInAnyOrder(b, c);
    }

    @Test
    @DisplayName("an artist already decided by the owner (SEED/APPROVED/REJECTED/REMOVED) is excluded even "
            + "though it's graph-reachable -- only undecided PENDING_REVIEW candidates count as new discovery")
    void excludesArtistsAlreadyDecidedByOwner() {
        Long seed = createArtist(OWNER, "A", ArtistStatus.SEED);
        Long approved = createArtist(OWNER, "Already approved", ArtistStatus.APPROVED);
        Long rejected = createArtist(OWNER, "Already rejected", ArtistStatus.REJECTED);
        Long anotherSeed = createArtist(OWNER, "Already a seed", ArtistStatus.SEED);
        Long removed = createArtist(OWNER, "Already removed", ArtistStatus.REMOVED);
        Long stillPending = createArtist(OWNER, "Still pending", ArtistStatus.PENDING_REVIEW);
        saveEdge(OWNER, seed, approved, "MEMBER_OF", "musicbrainz");
        saveEdge(OWNER, seed, rejected, "MEMBER_OF", "musicbrainz");
        saveEdge(OWNER, seed, anotherSeed, "MEMBER_OF", "musicbrainz");
        saveEdge(OWNER, seed, removed, "MEMBER_OF", "musicbrainz");
        saveEdge(OWNER, seed, stillPending, "MEMBER_OF", "musicbrainz");

        List<DiscoveredConnection> discovered = connectionsService.discoverConnections(OWNER);

        assertThat(discovered).extracting(DiscoveredConnection::artistId).containsExactly(stillPending);
    }

    @Test
    @DisplayName("an artist reachable via multiple seeds/paths shows every path, not just one arbitrary one")
    void showsAllPathsWhenReachableMultipleWays() {
        Long seed1 = createArtist(OWNER, "Radiohead", ArtistStatus.SEED);
        Long seed2 = createArtist(OWNER, "Portishead", ArtistStatus.SEED);
        Long target = createArtist(OWNER, "Thom Yorke", ArtistStatus.PENDING_REVIEW);
        saveEdge(OWNER, seed1, target, "MEMBER_OF", "musicbrainz");
        saveEdge(OWNER, seed1, target, "SIMILAR_TO", "lastfm");
        saveEdge(OWNER, seed2, target, "SIMILAR_TO", "lastfm");

        List<DiscoveredConnection> discovered = connectionsService.discoverConnections(OWNER);

        assertThat(discovered).hasSize(1);
        assertThat(discovered.get(0).paths()).containsExactlyInAnyOrder(
                new ConnectionPath("Radiohead", null, 1, "MEMBER_OF", "musicbrainz"),
                new ConnectionPath("Radiohead", null, 1, "SIMILAR_TO", "lastfm"),
                new ConnectionPath("Portishead", null, 1, "SIMILAR_TO", "lastfm"));
    }

    @Test
    @DisplayName("owner isolation: another owner's active artists, edges, and candidates never appear")
    void isOwnerScoped() {
        Long seed = createArtist(OWNER, "A", ArtistStatus.SEED);
        Long target = createArtist(OWNER, "B", ArtistStatus.PENDING_REVIEW);
        saveEdge(OWNER, seed, target, "MEMBER_OF", "musicbrainz");

        Long otherSeed = createArtist(OTHER_OWNER, "A", ArtistStatus.SEED);
        Long otherTarget = createArtist(OTHER_OWNER, "B", ArtistStatus.PENDING_REVIEW);
        saveEdge(OTHER_OWNER, otherSeed, otherTarget, "MEMBER_OF", "musicbrainz");

        List<DiscoveredConnection> discovered = connectionsService.discoverConnections(OWNER);
        assertThat(discovered).extracting(DiscoveredConnection::artistId).containsExactly(target);
        assertThat(discovered.get(0).paths()).extracting(ConnectionPath::seedName).containsExactly("A");

        List<DiscoveredConnection> otherDiscovered = connectionsService.discoverConnections(OTHER_OWNER);
        assertThat(otherDiscovered).extracting(DiscoveredConnection::artistId).containsExactly(otherTarget);
    }

    @Test
    @DisplayName("no active artists means no connections, without error")
    void noActiveArtistsMeansEmptyResult() {
        createArtist(OWNER, "Just pending", ArtistStatus.PENDING_REVIEW);

        List<DiscoveredConnection> discovered = connectionsService.discoverConnections(OWNER);

        assertThat(discovered).isEmpty();
    }

    @Test
    @DisplayName("results are sorted by artist name for a stable page render")
    void resultsAreSortedByName() {
        Long seed = createArtist(OWNER, "A", ArtistStatus.SEED);
        Long zed = createArtist(OWNER, "Zed Band", ArtistStatus.PENDING_REVIEW);
        Long apple = createArtist(OWNER, "Apple Band", ArtistStatus.PENDING_REVIEW);
        saveEdge(OWNER, seed, zed, "MEMBER_OF", "musicbrainz");
        saveEdge(OWNER, seed, apple, "MEMBER_OF", "musicbrainz");

        List<DiscoveredConnection> discovered = connectionsService.discoverConnections(OWNER);

        assertThat(discovered).extracting(DiscoveredConnection::artistName)
                .containsExactly("Apple Band", "Zed Band");
    }
}
