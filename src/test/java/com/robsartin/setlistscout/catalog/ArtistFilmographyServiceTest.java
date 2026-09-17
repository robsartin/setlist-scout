package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.MusicBrainzService;
import com.robsartin.setlistscout.shared.WikidataEntity;
import com.robsartin.setlistscout.shared.WikidataFilmCredit;
import com.robsartin.setlistscout.shared.WikidataService;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #286: resolving an artist to a Wikidata entity, and turning that entity's filmography
 * into works and credits.
 *
 * <p>The assertions that matter here are the refusals. A wrong QID is well-formed, resolves
 * cleanly, and attaches somebody else's filmography, so "left unresolved" is a result and not a
 * failure; and a film must never become an {@code artist} row, which is the same guard #284 put
 * on cinema scans.
 */
@SpringBootTest
@Testcontainers
class ArtistFilmographyServiceTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "filmography-test@example.com";

    @MockitoBean MusicBrainzService musicBrainz;
    @MockitoBean WikidataService wikidata;

    @Autowired WikidataIdentityService identities;
    @Autowired ArtistFilmographyService filmographies;
    @Autowired ArtistRepository artists;
    @Autowired WorkRepository works;
    @Autowired PersonWorkEdgeRepository edges;
    @Autowired JdbcTemplate jdbc;

    private Long artistId;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM person_work_edge WHERE owner = ?", OWNER);
        jdbc.update("DELETE FROM work WHERE owner = ?", OWNER);
        jdbc.update("DELETE FROM artist WHERE owner = ?", OWNER);
        artistId = anArtist("Martin Scorsese");
    }

    /**
     * Inserted through JdbcTemplate rather than {@code ArtistRepository#insertIfAbsent}: the
     * repository's native @Modifying insert needs a transaction, and a @BeforeEach has none.
     */
    private Long anArtist(String name) {
        jdbc.update("INSERT INTO artist (owner, name, normalized_name, source, status, created_at) "
                        + "VALUES (?, ?, ?, 'SEED_LIST', 'SEED', now())",
                OWNER, name, ArtistNameNormalizer.normalize(name));
        return jdbc.queryForObject("SELECT id FROM artist WHERE owner = ? AND normalized_name = ?",
                Long.class, OWNER, ArtistNameNormalizer.normalize(name));
    }

    private Artist reloaded() {
        return artists.findById(artistId).orElseThrow();
    }

    // ---- identity -----------------------------------------------------------

    @Test
    @DisplayName("prefers MusicBrainz's curated link and never searches Wikidata by name")
    void shouldPreferMusicBrainzAndNotSearchByName() {
        when(musicBrainz.findWikidataQid("Martin Scorsese")).thenReturn(Optional.of("Q41148"));
        when(wikidata.describe("Q41148")).thenReturn(Optional.of(
                new WikidataEntity("Q41148", "Martin Scorsese", "American filmmaker (born 1942)")));

        assertThat(identities.resolve(OWNER, artistId)).map(WikidataEntity::qid).contains("Q41148");

        verify(wikidata, never()).resolveByExactLabel(anyString());
        Artist artist = reloaded();
        assertThat(artist.getWikidataQid()).isEqualTo("Q41148");
        assertThat(artist.getWikidataDescription()).isEqualTo("American filmmaker (born 1942)");
    }

    @Test
    @DisplayName("falls back to an exact-label search when MusicBrainz has no link")
    void shouldFallBackToNameSearch() {
        when(musicBrainz.findWikidataQid("Martin Scorsese")).thenReturn(Optional.empty());
        when(wikidata.resolveByExactLabel("Martin Scorsese")).thenReturn(Optional.of(
                new WikidataEntity("Q41148", "Martin Scorsese", "American filmmaker (born 1942)")));

        assertThat(identities.resolve(OWNER, artistId)).map(WikidataEntity::qid).contains("Q41148");
        assertThat(reloaded().getWikidataQid()).isEqualTo("Q41148");
    }

    @Test
    @DisplayName("leaves an artist unresolved when neither source can identify them")
    void shouldLeaveArtistUnresolvedWhenAmbiguous() {
        when(musicBrainz.findWikidataQid("Martin Scorsese")).thenReturn(Optional.empty());
        when(wikidata.resolveByExactLabel("Martin Scorsese")).thenReturn(Optional.empty());

        assertThat(identities.resolve(OWNER, artistId)).isEmpty();
        assertThat(reloaded().getWikidataQid()).isNull();
    }

    @Test
    @DisplayName("does not look an artist up twice once they are resolved")
    void shouldNotLookUpAnAlreadyResolvedArtist() {
        when(musicBrainz.findWikidataQid("Martin Scorsese")).thenReturn(Optional.of("Q41148"));
        when(wikidata.describe("Q41148")).thenReturn(Optional.of(
                new WikidataEntity("Q41148", "Martin Scorsese", "American filmmaker (born 1942)")));
        identities.resolve(OWNER, artistId);

        identities.resolve(OWNER, artistId);

        verify(musicBrainz, org.mockito.Mockito.times(1)).findWikidataQid("Martin Scorsese");
    }

    // ---- filmography --------------------------------------------------------

    private void resolvedWithFilmography(List<WikidataFilmCredit> credits) {
        when(musicBrainz.findWikidataQid("Martin Scorsese")).thenReturn(Optional.of("Q41148"));
        when(wikidata.describe("Q41148")).thenReturn(Optional.of(
                new WikidataEntity("Q41148", "Martin Scorsese", "American filmmaker (born 1942)")));
        when(wikidata.filmography("Q41148")).thenReturn(credits);
    }

    @Test
    @DisplayName("writes one work per film and one credit per role")
    void shouldWriteOneWorkPerFilmAndOneCreditPerRole() {
        resolvedWithFilmography(List.of(
                new WikidataFilmCredit("Q154581", "Gangs of New York", 2002, "DIRECTED"),
                new WikidataFilmCredit("Q154581", "Gangs of New York", 2002, "ACTED_IN"),
                new WikidataFilmCredit("Q182218", "The Departed", 2006, "DIRECTED")));

        assertThat(filmographies.refresh(OWNER, artistId)).isEqualTo(3);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE owner = ?", Long.class, OWNER))
                .isEqualTo(2);
        assertThat(edges.findByOwnerAndArtistId(OWNER, artistId)).hasSize(3);
    }

    @Test
    @DisplayName("creates no artist rows -- a film never enters the catalog")
    void shouldCreateNoArtistRows() {
        long before = jdbc.queryForObject("SELECT count(*) FROM artist WHERE owner = ?", Long.class, OWNER);
        resolvedWithFilmography(List.of(
                new WikidataFilmCredit("Q154581", "Gangs of New York", 2002, "DIRECTED"),
                new WikidataFilmCredit("Q182218", "The Departed", 2006, "DIRECTED")));

        filmographies.refresh(OWNER, artistId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM artist WHERE owner = ?", Long.class, OWNER))
                .isEqualTo(before);
    }

    @Test
    @DisplayName("a second refresh writes nothing new")
    void shouldBeIdempotentAcrossRefreshes() {
        resolvedWithFilmography(List.of(
                new WikidataFilmCredit("Q154581", "Gangs of New York", 2002, "DIRECTED")));
        filmographies.refresh(OWNER, artistId);

        filmographies.refresh(OWNER, artistId);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE owner = ?", Long.class, OWNER))
                .isEqualTo(1);
        assertThat(edges.findByOwnerAndArtistId(OWNER, artistId)).hasSize(1);
    }

    @Test
    @DisplayName("writes nothing for an artist who cannot be resolved")
    void shouldWriteNothingForAnUnresolvableArtist() {
        when(musicBrainz.findWikidataQid("Martin Scorsese")).thenReturn(Optional.empty());
        when(wikidata.resolveByExactLabel("Martin Scorsese")).thenReturn(Optional.empty());

        assertThat(filmographies.refresh(OWNER, artistId)).isZero();

        verify(wikidata, never()).filmography(anyString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM work WHERE owner = ?", Long.class, OWNER))
                .isZero();
    }

    @Test
    @DisplayName("stores a film that states no release year rather than dropping it")
    void shouldStoreAFilmWithNoYear() {
        resolvedWithFilmography(List.of(
                new WikidataFilmCredit("Q999999", "Untitled Short", null, "DIRECTED")));

        filmographies.refresh(OWNER, artistId);

        assertThat(works.findByOwnerAndWikidataQid(OWNER, "Q999999"))
                .get().extracting(Work::getReleaseYear).isNull();
    }

    @Test
    @DisplayName("groups an artist's credits into one row per film, newest first")
    void shouldGroupCreditsByFilmNewestFirst() {
        resolvedWithFilmography(List.of(
                new WikidataFilmCredit("Q154581", "Gangs of New York", 2002, "DIRECTED"),
                new WikidataFilmCredit("Q154581", "Gangs of New York", 2002, "ACTED_IN"),
                new WikidataFilmCredit("Q182218", "The Departed", 2006, "DIRECTED")));
        filmographies.refresh(OWNER, artistId);

        List<WorkCreditView> credits = filmographies.creditsFor(OWNER, artistId);

        assertThat(credits).extracting(WorkCreditView::titleWithYear)
                .containsExactly("The Departed (2006)", "Gangs of New York (2002)");
        assertThat(credits.get(1).roles()).containsExactlyInAnyOrder("DIRECTED", "ACTED_IN");
    }
}
