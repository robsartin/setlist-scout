package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.expansion.ExpandUnitRunner;
import com.robsartin.setlistscout.expansion.source.DiscogsRelationSource;
import com.robsartin.setlistscout.expansion.source.MusicBrainzRelationSource;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Real-path Testcontainers proof of issue #109 task 2's wiring: {@link ExpandUnitRunner} publishes
 * a {@code RelationDiscovered} event inside a committed transaction, and the real
 * {@code RelationDiscoveredListener} (an {@code @ApplicationModuleListener}) persists BOTH the
 * PENDING_REVIEW to-artist node AND the {@code artist_edge} row, in one listener transaction --
 * no async node/edge ordering race, matching {@code PollerFlowTest.expandHappyPath}'s style for
 * the {@code CandidateDiscovered} predecessor of this event.
 * <p>
 * {@link #corroborationFromTwoSourcesDedupesNodeButPreservesBothEdges()} is the model-level point
 * of #109 (see {@code docs/explorations/2026-08-14-artist-graph-model.md} section 1): the retired
 * {@code CandidatePersistenceListener} short-circuited on an already-known candidate name, so a
 * second source corroborating the same relationship was silently dropped -- only the first
 * source's {@code source}/{@code note} survived anywhere. This proves the fix: two different
 * {@code RelationSource}s (MusicBrainz, Discogs) asserting the same (owner, from, to, type)
 * relationship produce ONE deduplicated candidate node but TWO distinct {@code artist_edge} rows,
 * one per source.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false"
})
class RelationDiscoveredFlowTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "relation-flow@example.com";
    private static final String BASE_ARTIST_NAME = "Relation Flow Base Artist";
    private static final String CANDIDATE_NAME = "Relation Flow Candidate";

    @Autowired
    private ExpandUnitRunner expandUnitRunner;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ArtistEdgeRepository artistEdgeRepository;

    @MockitoBean
    private MusicBrainzRelationSource musicBrainzRelationSource;

    @MockitoBean
    private DiscogsRelationSource discogsRelationSource;

    private Long baseArtistId;

    @BeforeEach
    void setUp() {
        // The Testcontainers Postgres instance (and Spring context) is shared across every test
        // method in this class, so a prior method's committed rows survive into the next one --
        // clear both tables first, same as ArtistRepositoryTest/ArtistEdgeRepositoryTest, or the
        // second test's base-artist insert below collides with the first test's (owner, name).
        artistEdgeRepository.deleteAll();
        artistRepository.deleteAll();

        Artist base = new Artist(BASE_ARTIST_NAME, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        base.setOwner(OWNER);
        baseArtistId = artistRepository.save(base).getId();

        when(musicBrainzRelationSource.id()).thenReturn("musicbrainz");
        when(musicBrainzRelationSource.classification()).thenReturn(ArtistSource.MEMBER_EXPANSION);
        when(musicBrainzRelationSource.note(BASE_ARTIST_NAME))
                .thenReturn("member/lineup relation of " + BASE_ARTIST_NAME);

        when(discogsRelationSource.id()).thenReturn("discogs");
        when(discogsRelationSource.classification()).thenReturn(ArtistSource.MEMBER_EXPANSION);
        when(discogsRelationSource.note(BASE_ARTIST_NAME))
                .thenReturn("member/lineup relation of " + BASE_ARTIST_NAME);
    }

    @Test
    @DisplayName("expansion publishes RelationDiscovered, and the real listener creates a "
            + "PENDING_REVIEW node and an artist_edge row from the base artist to it")
    void createsNodeAndEdgeFromOneRelation() {
        when(musicBrainzRelationSource.related(BASE_ARTIST_NAME)).thenReturn(List.of(CANDIDATE_NAME));

        expandUnitRunner.run(OWNER, baseArtistId, "musicbrainz", BASE_ARTIST_NAME);

        Artist candidate = awaitUntil(
                () -> artistRepository.findByOwnerAndName(OWNER, CANDIDATE_NAME).orElse(null),
                a -> a != null);
        assertThat(candidate).as("candidate node persisted via the real listener").isNotNull();
        assertThat(candidate.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(candidate.getDiscoveredVia()).isEqualTo(BASE_ARTIST_NAME);

        List<ArtistEdge> edges = awaitUntil(
                () -> artistEdgeRepository.findByOwnerAndFromArtistId(OWNER, baseArtistId),
                l -> !l.isEmpty());
        assertThat(edges).as("edge persisted via the real listener").hasSize(1);
        ArtistEdge edge = edges.get(0);
        assertThat(edge.getToArtistId()).isEqualTo(candidate.getId());
        assertThat(edge.getType()).isEqualTo(ArtistSource.MEMBER_EXPANSION.name());
        assertThat(edge.getSource()).isEqualTo("musicbrainz");
    }

    @Test
    @DisplayName("corroboration: two RelationSources asserting the same relationship create ONE "
            + "deduplicated candidate node but TWO distinct artist_edge rows -- the #109 fix for "
            + "the silent corroboration loss the old CandidateDiscovered path had")
    void corroborationFromTwoSourcesDedupesNodeButPreservesBothEdges() {
        when(musicBrainzRelationSource.related(BASE_ARTIST_NAME)).thenReturn(List.of(CANDIDATE_NAME));
        when(discogsRelationSource.related(BASE_ARTIST_NAME)).thenReturn(List.of(CANDIDATE_NAME));

        expandUnitRunner.run(OWNER, baseArtistId, "musicbrainz", BASE_ARTIST_NAME);
        expandUnitRunner.run(OWNER, baseArtistId, "discogs", BASE_ARTIST_NAME);

        List<ArtistEdge> edges = awaitUntil(
                () -> artistEdgeRepository.findByOwnerAndFromArtistId(OWNER, baseArtistId),
                l -> l.size() >= 2);
        assertThat(edges).as("both sources' assertions survive as distinct edges").hasSize(2);
        assertThat(edges).extracting(ArtistEdge::getSource)
                .containsExactlyInAnyOrder("musicbrainz", "discogs");
        assertThat(edges).allMatch(e -> e.getType().equals(ArtistSource.MEMBER_EXPANSION.name()));

        List<Artist> candidates = artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW).stream()
                .filter(a -> CANDIDATE_NAME.equals(a.getName()))
                .toList();
        assertThat(candidates).as("ONE deduplicated node, not one per source").hasSize(1);
    }
}
