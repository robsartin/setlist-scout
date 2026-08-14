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
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Testcontainers-backed proof of {@link ArtistEdgeRepository#insertIfAbsent}'s {@code ON CONFLICT
 * (owner, from_artist_id, to_artist_id, type, source) DO NOTHING} semantics against real Postgres
 * (issue #109 phase 1 -- the artist-relationship graph storage layer). Mirrors
 * {@code ArtistRepositoryTest}'s approach: a repository-level test against real, already-committed
 * rows is what actually proves the DB-level idempotent-upsert guard (ADR-0024), not a
 * higher-level listener test that would never reach a genuine conflict.
 *
 * <p>The corroboration test is the model-level point of this table (see
 * {@code docs/explorations/2026-08-14-artist-graph-model.md} section 1): two different sources
 * asserting the same (owner, from, to, type) relationship must both survive as distinct edges,
 * unlike today's flat {@code Artist.source}/{@code discoveredVia} columns, which silently drop
 * the second discovery.
 */
@SpringBootTest
@Testcontainers
class ArtistEdgeRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "artist-edge-test@example.com";
    private static final String OTHER_OWNER = "artist-edge-test-2@example.com";

    @Autowired
    private ArtistEdgeRepository artistEdgeRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @BeforeEach
    void clearTables() {
        artistEdgeRepository.deleteAll();
        artistRepository.deleteAll();
    }

    private Long createArtist(String owner, String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        return artistRepository.saveAndFlush(artist).getId();
    }

    @Test
    @DisplayName("insertIfAbsent creates an edge row that round-trips all fields via findByOwnerAndFromArtistId")
    // insertIfAbsent is a @Modifying native query with no @Transactional of its own (only ever
    // called from an already-transactional caller in production, mirroring ArtistRepository's
    // insertIfAbsent/ScanJobRepository's insertIfAbsent), so calling it directly here needs an
    // ambient transaction supplied by the test -- same pattern as ArtistRepositoryTest and
    // ScanJobRepositoryTest#redueAllResetsJobsAndBumpsVersion.
    @Transactional
    void insertIfAbsentCreatesEdgeRoundTrippingAllFields() {
        Long fromId = createArtist(OWNER, "Radiohead");
        Long toId = createArtist(OWNER, "Thom Yorke");
        Instant createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);

        artistEdgeRepository.insertIfAbsent(OWNER, fromId, toId, "MEMBER_EXPANSION", "musicbrainz",
                "member/lineup relation of Radiohead", 1.0, createdAt);

        List<ArtistEdge> found = artistEdgeRepository.findByOwnerAndFromArtistId(OWNER, fromId);
        assertThat(found).hasSize(1);
        ArtistEdge edge = found.get(0);
        assertThat(edge.getOwner()).isEqualTo(OWNER);
        assertThat(edge.getFromArtistId()).isEqualTo(fromId);
        assertThat(edge.getToArtistId()).isEqualTo(toId);
        assertThat(edge.getType()).isEqualTo("MEMBER_EXPANSION");
        assertThat(edge.getSource()).isEqualTo("musicbrainz");
        assertThat(edge.getNote()).isEqualTo("member/lineup relation of Radiohead");
        assertThat(edge.getWeight()).isEqualTo(1.0);
        assertThat(edge.getCreatedAt()).isEqualTo(createdAt);
    }

    @Test
    @DisplayName("a second insertIfAbsent with the same (owner, from, to, type, source) is a silent "
            + "no-op: no exception, no duplicate row")
    @Transactional
    void insertIfAbsentIsIdempotentAgainstAMatchingEdge() {
        Long fromId = createArtist(OWNER, "Radiohead");
        Long toId = createArtist(OWNER, "Thom Yorke");

        artistEdgeRepository.insertIfAbsent(OWNER, fromId, toId, "MEMBER_EXPANSION", "musicbrainz",
                "first note", null, Instant.now());

        assertThatCode(() -> artistEdgeRepository.insertIfAbsent(OWNER, fromId, toId, "MEMBER_EXPANSION",
                "musicbrainz", "racing note", null, Instant.now()))
                .as("ON CONFLICT DO NOTHING never throws")
                .doesNotThrowAnyException();

        List<ArtistEdge> found = artistEdgeRepository.findByOwnerAndFromArtistId(OWNER, fromId);
        assertThat(found).as("no duplicate row").hasSize(1);
        assertThat(found.get(0).getNote())
                .as("DO NOTHING means the original row wins")
                .isEqualTo("first note");
    }

    @Test
    @DisplayName("corroboration: two sources asserting the same (owner, from, to, type) relationship "
            + "create two distinct edges -- the model-level proof that provenance from both sources "
            + "is preserved instead of silently dropped")
    @Transactional
    void insertIfAbsentPreservesCorroborationFromDifferentSources() {
        Long fromId = createArtist(OWNER, "Radiohead");
        Long toId = createArtist(OWNER, "Thom Yorke");

        artistEdgeRepository.insertIfAbsent(OWNER, fromId, toId, "MEMBER_EXPANSION", "musicbrainz",
                "member/lineup relation of Radiohead", null, Instant.now());
        artistEdgeRepository.insertIfAbsent(OWNER, fromId, toId, "MEMBER_EXPANSION", "discogs",
                "member/lineup relation of Radiohead", null, Instant.now());

        List<ArtistEdge> found = artistEdgeRepository.findByOwnerAndFromArtistId(OWNER, fromId);
        assertThat(found).as("both sources' assertions survive as distinct edges").hasSize(2);
        assertThat(found).extracting(ArtistEdge::getSource)
                .containsExactlyInAnyOrder("musicbrainz", "discogs");
    }

    @Test
    @DisplayName("owner isolation: another owner's edge for the same from/to artist ids is not returned")
    @Transactional
    void findByOwnerAndFromArtistIdIsOwnerScoped() {
        Long fromId = createArtist(OWNER, "Radiohead");
        Long toId = createArtist(OWNER, "Thom Yorke");
        Long otherFromId = createArtist(OTHER_OWNER, "Radiohead");
        Long otherToId = createArtist(OTHER_OWNER, "Thom Yorke");

        artistEdgeRepository.insertIfAbsent(OWNER, fromId, toId, "MEMBER_EXPANSION", "musicbrainz",
                null, null, Instant.now());
        artistEdgeRepository.insertIfAbsent(OTHER_OWNER, otherFromId, otherToId, "MEMBER_EXPANSION",
                "musicbrainz", null, null, Instant.now());

        List<ArtistEdge> found = artistEdgeRepository.findByOwnerAndFromArtistId(OWNER, fromId);
        assertThat(found).hasSize(1);
        assertThat(found.get(0).getOwner()).isEqualTo(OWNER);

        List<ArtistEdge> toFound = artistEdgeRepository.findByOwnerAndToArtistId(OWNER, toId);
        assertThat(toFound).hasSize(1);
        assertThat(toFound.get(0).getOwner()).isEqualTo(OWNER);
    }
}
