package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real {@code /artists/{id}/films} page (#286) against a booted context + Postgres.
 *
 * <p>Drives the actual Thymeleaf template on purpose: a broken expression compiles cleanly and
 * 500s at runtime, so nothing short of rendering proves the page works. Each test uses a distinct
 * owner so data cannot leak between methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ArtistFilmsPageRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private ArtistRepository artistRepository;
    @Autowired private JdbcTemplate jdbc;

    private Long saveArtist(String owner, String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist).getId();
    }

    private void resolve(Long artistId, String qid, String label, String description) {
        jdbc.update("UPDATE artist SET wikidata_qid = ?, wikidata_label = ?, wikidata_description = ?"
                + " WHERE id = ?", qid, label, description, artistId);
    }

    private Long saveWork(String owner, String qid, String title, String normalized, Integer year) {
        jdbc.update("INSERT INTO work (owner, wikidata_qid, title, normalized_title, release_year, created_at)"
                + " VALUES (?, ?, ?, ?, ?, now())", owner, qid, title, normalized, year);
        return jdbc.queryForObject("SELECT id FROM work WHERE owner = ? AND wikidata_qid = ?",
                Long.class, owner, qid);
    }

    private void saveCredit(String owner, Long artistId, Long workId, String role) {
        jdbc.update("INSERT INTO person_work_edge (owner, artist_id, work_id, role, source, created_at)"
                + " VALUES (?, ?, ?, ?, 'wikidata', now())", owner, artistId, workId, role);
    }

    @Test
    @DisplayName("lists an artist's films newest first, with every role they had")
    void shouldListFilmsNewestFirstWithRoles() throws Exception {
        String owner = "render-films@example.com";
        Long scorsese = saveArtist(owner, "Martin Scorsese");
        resolve(scorsese, "Q41148", "Martin Scorsese", "American filmmaker (born 1942)");
        Long gangs = saveWork(owner, "Q154581", "Gangs of New York", "gangs of new york", 2002);
        Long departed = saveWork(owner, "Q182218", "The Departed", "departed", 2006);
        saveCredit(owner, scorsese, gangs, "DIRECTED");
        saveCredit(owner, scorsese, gangs, "ACTED_IN");
        saveCredit(owner, scorsese, departed, "DIRECTED");

        String body = mockMvc.perform(get("/artists/" + scorsese + "/films")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("The Departed (2006)")))
                .andExpect(content().string(containsString("Gangs of New York (2002)")))
                .andExpect(content().string(containsString("ACTED_IN")))
                // The resolved identity is on the page so a wrong match is visible, not just a QID.
                .andExpect(content().string(containsString("American filmmaker (born 1942)")))
                .andReturn().getResponse().getContentAsString();

        assertThat(body.indexOf("The Departed")).isLessThan(body.indexOf("Gangs of New York"));
    }

    @Test
    @DisplayName("tells an unresolved artist's story rather than showing an empty table")
    void shouldExplainAnUnresolvedArtist() throws Exception {
        String owner = "render-films-unresolved@example.com";
        Long artistId = saveArtist(owner, "Willie Nelson");

        mockMvc.perform(get("/artists/" + artistId + "/films")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Not yet matched to Wikidata")))
                .andExpect(content().string(containsString("Find films")));
    }

    @Test
    @DisplayName("says so plainly when a resolved artist simply made no films")
    void shouldSayWhenAResolvedArtistHasNoFilms() throws Exception {
        String owner = "render-films-none@example.com";
        Long artistId = saveArtist(owner, "Spoon");
        resolve(artistId, "Q1155915", "Spoon", "American rock band");

        mockMvc.perform(get("/artists/" + artistId + "/films")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("No films recorded")))
                .andExpect(content().string(not(containsString("Not yet matched to Wikidata"))));
    }

    @Test
    @DisplayName("the artists list links to each artist's films page")
    void shouldLinkToFilmsFromTheArtistsList() throws Exception {
        String owner = "render-films-link@example.com";
        Long artistId = saveArtist(owner, "Martin Scorsese");

        mockMvc.perform(get("/artists")
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/artists/" + artistId + "/films")));
    }

    @Test
    @DisplayName("another owner's artist is not found, rather than visible")
    void shouldNotFindAnotherOwnersArtist() throws Exception {
        Long artistId = saveArtist("films-owner-a@example.com", "Martin Scorsese");

        mockMvc.perform(get("/artists/" + artistId + "/films")
                        .with(oidcLogin().idToken(t -> t.claim("email", "films-owner-b@example.com"))))
                .andExpect(status().isNotFound());
    }

    private static org.assertj.core.api.AbstractIntegerAssert<?> assertThat(int actual) {
        return org.assertj.core.api.Assertions.assertThat(actual);
    }
}
