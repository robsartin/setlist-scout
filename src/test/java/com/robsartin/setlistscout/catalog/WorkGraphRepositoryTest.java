package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
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
 * Testcontainers-backed proof that the work graph's two writes are idempotent at the database
 * level (#286), the way {@code ArtistEdgeRepositoryTest} proves it for {@code artist_edge}.
 *
 * <p>Idempotency is not a nicety here. A filmography is re-fetched every time an artist is
 * refreshed, and it returns the same 112 films each time; without {@code ON CONFLICT DO NOTHING}
 * the second refresh of any artist throws, and ADR-0024's durable-write invariant means that
 * throw takes the whole transaction -- and the event that depends on it -- with it.
 */
@SpringBootTest
@Testcontainers
class WorkGraphRepositoryTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "work-graph-test@example.com";

    @Autowired WorkRepository works;
    @Autowired PersonWorkEdgeRepository edges;
    @Autowired ArtistRepository artists;

    private Long anArtist(String name) {
        artists.insertIfAbsent(OWNER, name, ArtistNameNormalizer.normalize(name),
                ArtistSource.SEED_LIST.name(), ArtistStatus.SEED.name(), null, null, Instant.now());
        return artists.findByOwnerAndNormalizedName(OWNER, ArtistNameNormalizer.normalize(name))
                .orElseThrow().getId();
    }

    @Test
    @Transactional
    @DisplayName("re-inserting the same work is a no-op, not a constraint violation")
    void shouldTreatARepeatedWorkAsANoOp() {
        works.insertIfAbsent(OWNER, "Q154581", "Gangs of New York", "gangs of new york", 2002, Instant.now());

        assertThatCode(() -> works.insertIfAbsent(
                OWNER, "Q154581", "Gangs of New York", "gangs of new york", 2002, Instant.now()))
                .doesNotThrowAnyException();

        assertThat(works.findByOwnerAndWikidataQid(OWNER, "Q154581")).isPresent();
    }

    @Test
    @Transactional
    @DisplayName("two films sharing a title are two works, because identity is the QID")
    void shouldKeepTwoFilmsThatShareATitle() {
        works.insertIfAbsent(OWNER, "Q191518", "Dune", "dune", 1984, Instant.now());
        works.insertIfAbsent(OWNER, "Q56239097", "Dune", "dune", 2021, Instant.now());

        assertThat(works.findByOwnerAndWikidataQid(OWNER, "Q191518")).isPresent();
        assertThat(works.findByOwnerAndWikidataQid(OWNER, "Q56239097")).isPresent();
    }

    @Test
    @Transactional
    @DisplayName("re-inserting the same credit is a no-op, but another source corroborates it")
    void shouldTreatARepeatedCreditAsANoOpAndKeepCorroboration() {
        Long artistId = anArtist("Martin Scorsese");
        works.insertIfAbsent(OWNER, "Q154581", "Gangs of New York", "gangs of new york", 2002, Instant.now());
        Long workId = works.findByOwnerAndWikidataQid(OWNER, "Q154581").orElseThrow().getId();

        edges.insertIfAbsent(OWNER, artistId, workId, "DIRECTED", "wikidata", Instant.now());
        assertThatCode(() -> edges.insertIfAbsent(
                OWNER, artistId, workId, "DIRECTED", "wikidata", Instant.now()))
                .doesNotThrowAnyException();
        edges.insertIfAbsent(OWNER, artistId, workId, "DIRECTED", "imdb", Instant.now());
        edges.insertIfAbsent(OWNER, artistId, workId, "ACTED_IN", "wikidata", Instant.now());

        List<PersonWorkEdge> found = edges.findByOwnerAndArtistId(OWNER, artistId);
        assertThat(found).extracting(PersonWorkEdge::getRole, PersonWorkEdge::getSource)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("DIRECTED", "wikidata"),
                        org.assertj.core.groups.Tuple.tuple("DIRECTED", "imdb"),
                        org.assertj.core.groups.Tuple.tuple("ACTED_IN", "wikidata"));
    }
}
