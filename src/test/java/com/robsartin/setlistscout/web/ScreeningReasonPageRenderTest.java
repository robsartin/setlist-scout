package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #289 (Films 3/3): the Shows page says WHY a screening is worth seeing, and ranks screenings
 * that have a reason above ones that do not.
 *
 * <p>Renders the real template, because a Thymeleaf expression error compiles cleanly and 500s at
 * runtime. Each test uses a distinct owner so rows cannot leak between methods.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ScreeningReasonPageRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    private LocalDateTime soon;

    @BeforeEach
    void setUp() {
        soon = LocalDateTime.now().plusDays(3).withHour(19).withMinute(0).withSecond(0).withNano(0);
    }

    private void artist(String owner, String name, ArtistStatus status, Long workId, String role) {
        jdbc.update("INSERT INTO artist (owner, name, normalized_name, source, status, created_at)"
                        + " VALUES (?, ?, ?, 'SEED_LIST', ?, now())",
                owner, name, ArtistNameNormalizer.normalize(name), status.name());
        Long artistId = jdbc.queryForObject(
                "SELECT id FROM artist WHERE owner = ? AND normalized_name = ?",
                Long.class, owner, ArtistNameNormalizer.normalize(name));
        if (workId != null) {
            jdbc.update("INSERT INTO person_work_edge (owner, artist_id, work_id, role, source, created_at)"
                    + " VALUES (?, ?, ?, ?, 'wikidata', now())", owner, artistId, workId, role);
        }
    }

    private Long work(String owner, String qid, String title, Integer year) {
        jdbc.update("INSERT INTO work (owner, wikidata_qid, title, normalized_title, release_year, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, now())",
                owner, qid, title, ArtistNameNormalizer.normalize(title), year);
        return jdbc.queryForObject("SELECT id FROM work WHERE owner = ? AND wikidata_qid = ?",
                Long.class, owner, qid);
    }

    private void screening(String owner, String title, Integer year, LocalDateTime at) {
        jdbc.update("INSERT INTO show_event (owner, artist_name, event_date_time, venue_name, source,"
                        + " kind, release_year, discovered_at) VALUES (?, ?, ?, 'AFS Cinema',"
                        + " 'venue:afsfilm.org', 'FILM', ?, now())",
                owner, title, at, year);
    }

    private void concert(String owner, String act, LocalDateTime at) {
        jdbc.update("INSERT INTO show_event (owner, artist_name, event_date_time, venue_name, source,"
                        + " kind, discovered_at) VALUES (?, ?, ?, 'Mohawk', 'ticketmaster', 'MUSIC', now())",
                owner, act, at);
    }

    private String showsPage(String owner) throws Exception {
        return mockMvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("says why a screening is worth seeing, naming the person and their role")
    void shouldNameTheFollowedPersonAndRole() throws Exception {
        String owner = "reason-named@example.com";
        Long goodfellas = work(owner, "Q101540", "Goodfellas", 1990);
        artist(owner, "Martin Scorsese", ArtistStatus.SEED, goodfellas, "DIRECTED");
        screening(owner, "Goodfellas", 1990, soon);

        String body = showsPage(owner);

        assertThat(body).contains("Martin Scorsese").contains("Goodfellas");
        assertThat(body).containsIgnoringCase("because you follow");
    }

    @Test
    @DisplayName("a screening nobody followed worked on still appears, just without a reason")
    void shouldStillShowAnUnmatchedScreening() throws Exception {
        String owner = "reason-unmatched@example.com";
        screening(owner, "Some Obscure Film", 1974, soon);

        String body = showsPage(owner);

        assertThat(body).contains("Some Obscure Film");
        assertThat(body).doesNotContainIgnoringCase("because you follow");
    }

    @Test
    @DisplayName("a screening with a reason is ranked above one without, on the same day")
    void shouldRankMatchedScreeningsFirstWithinADay() throws Exception {
        String owner = "reason-ranked@example.com";
        Long goodfellas = work(owner, "Q101540", "Goodfellas", 1990);
        artist(owner, "Martin Scorsese", ArtistStatus.SEED, goodfellas, "DIRECTED");
        // The matched film screens LATER in the evening, so date order alone would put it second.
        screening(owner, "Some Obscure Film", 1974, soon);
        screening(owner, "Goodfellas", 1990, soon.plusHours(2));

        String body = showsPage(owner);

        assertThat(body.indexOf("Goodfellas")).isLessThan(body.indexOf("Some Obscure Film"));
    }

    @Test
    @DisplayName("ranking screenings leaves a concert on the same day exactly where it was")
    void shouldNotDisturbNonFilmShows() throws Exception {
        String owner = "reason-concert@example.com";
        Long goodfellas = work(owner, "Q101540", "Goodfellas", 1990);
        artist(owner, "Martin Scorsese", ArtistStatus.SEED, goodfellas, "DIRECTED");
        screening(owner, "Some Obscure Film", 1974, soon);
        concert(owner, "Wilco", soon.plusHours(1));
        screening(owner, "Goodfellas", 1990, soon.plusHours(2));

        String body = showsPage(owner);

        // The two screenings swap; the concert keeps the middle slot it held by time. Ranking is a
        // reordering among screenings, not a re-sort of the page.
        assertThat(body.indexOf("Goodfellas"))
                .isLessThan(body.indexOf("Wilco"));
        assertThat(body.indexOf("Wilco"))
                .isLessThan(body.indexOf("Some Obscure Film"));
    }
}
