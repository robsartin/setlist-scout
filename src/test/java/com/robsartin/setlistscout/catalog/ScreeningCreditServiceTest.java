package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #289 (Films 3/3): which followed people are credited on the film a screening is showing.
 *
 * <p>Most of these tests assert a <em>refusal</em>, because the rule is stricter than it looks and
 * the reason is measured rather than assumed. Seven different films are called "Titanic", two of
 * them a year apart (1996 and 1997); "Nosferatu" has a 2023 and a 2024. So a title is not an
 * identity and a tolerance wide enough to absorb a sloppy listing is wide enough to name the wrong
 * film. Missing a match is recoverable; attributing the wrong one is not.
 */
@SpringBootTest
@Testcontainers
class ScreeningCreditServiceTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "screening-credits@example.com";

    @Autowired ScreeningCreditService credits;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM person_work_edge WHERE owner = ?", OWNER);
        jdbc.update("DELETE FROM work WHERE owner = ?", OWNER);
        jdbc.update("DELETE FROM artist WHERE owner = ?", OWNER);
    }

    private Long artist(String name, ArtistStatus status) {
        jdbc.update("INSERT INTO artist (owner, name, normalized_name, source, status, created_at)"
                        + " VALUES (?, ?, ?, 'SEED_LIST', ?, now())",
                OWNER, name, ArtistNameNormalizer.normalize(name), status.name());
        return jdbc.queryForObject("SELECT id FROM artist WHERE owner = ? AND normalized_name = ?",
                Long.class, OWNER, ArtistNameNormalizer.normalize(name));
    }

    private Long work(String qid, String title, Integer year) {
        jdbc.update("INSERT INTO work (owner, wikidata_qid, title, normalized_title, release_year, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, now())",
                OWNER, qid, title, ArtistNameNormalizer.normalize(title), year);
        return jdbc.queryForObject("SELECT id FROM work WHERE owner = ? AND wikidata_qid = ?",
                Long.class, OWNER, qid);
    }

    private void credit(Long artistId, Long workId, String role) {
        jdbc.update("INSERT INTO person_work_edge (owner, artist_id, work_id, role, source, created_at)"
                + " VALUES (?, ?, ?, ?, 'wikidata', now())", OWNER, artistId, workId, role);
    }

    private ScreeningKey screening(String title, Integer year) {
        return new ScreeningKey(ArtistNameNormalizer.normalize(title), year);
    }

    private List<ScreeningCredit> lookup(ScreeningKey key) {
        Map<ScreeningKey, List<ScreeningCredit>> found = credits.creditsFor(OWNER, List.of(key));
        return found.getOrDefault(key, List.of());
    }

    // ---- the matching rule --------------------------------------------------

    @Test
    @DisplayName("a title only one film has matches even when the listing states no year")
    void shouldMatchAnUnambiguousTitleWithoutAYear() {
        Long scorsese = artist("Martin Scorsese", ArtistStatus.SEED);
        Long goodfellas = work("Q101540", "Goodfellas", 1990);
        credit(scorsese, goodfellas, "DIRECTED");

        assertThat(lookup(screening("Goodfellas", null)))
                .extracting(ScreeningCredit::artistName).containsExactly("Martin Scorsese");
    }

    @Test
    @DisplayName("a title several films share matches nothing when the listing states no year")
    void shouldRefuseAnAmbiguousTitleWithoutAYear() {
        Long cameron = artist("James Cameron", ArtistStatus.SEED);
        Long titanic1997 = work("Q44578", "Titanic", 1997);
        Long titanic1996 = work("Q1332341", "Titanic", 1996);
        credit(cameron, titanic1997, "DIRECTED");
        credit(cameron, titanic1996, "DIRECTED");

        assertThat(lookup(screening("Titanic", null))).isEmpty();
    }

    @Test
    @DisplayName("a year one apart is a different film, not a near miss")
    void shouldNotMatchAcrossASingleYear() {
        Long cameron = artist("James Cameron", ArtistStatus.SEED);
        Long titanic1997 = work("Q44578", "Titanic", 1997);
        Long titanic1996 = work("Q1332341", "Titanic", 1996);
        credit(cameron, titanic1997, "DIRECTED");

        // The 1996 film is the one screening; Cameron directed the 1997 one. No reason, not a
        // near-enough reason -- Titanic really does have a 1996 and a 1997.
        assertThat(lookup(screening("Titanic", 1996))).isEmpty();
        assertThat(lookup(screening("Titanic", 1997)))
                .extracting(ScreeningCredit::artistName).containsExactly("James Cameron");
    }

    @Test
    @DisplayName("two films sharing a title AND a year match nothing, even with the year given")
    void shouldRefuseWhenEvenTheYearCannotSeparateThem() {
        Long someone = artist("Some Director", ArtistStatus.SEED);
        Long a = work("Q44578", "Titanic", 1997);
        Long b = work("Q9999991", "Titanic", 1997);
        credit(someone, a, "DIRECTED");
        credit(someone, b, "DIRECTED");

        assertThat(lookup(screening("Titanic", 1997))).isEmpty();
    }

    @Test
    @DisplayName("an ambiguous title matches nothing when the WORK is the side missing a year")
    void shouldRefuseWhenTheWorkHasNoYear() {
        Long someone = artist("Some Director", ArtistStatus.SEED);
        Long dated = work("Q44578", "Titanic", 1997);
        Long undated = work("Q9999991", "Titanic", null);
        credit(someone, dated, "DIRECTED");
        credit(someone, undated, "DIRECTED");

        // 1997 separates them here, so this one still resolves -- the undated row simply cannot win.
        assertThat(lookup(screening("Titanic", 1997)))
                .extracting(ScreeningCredit::artistName).containsExactly("Some Director");
        // ...but nothing can pick the undated film, and guessing is the failure being avoided.
        assertThat(lookup(screening("Titanic", 1912))).isEmpty();
    }

    // ---- who counts as a reason ---------------------------------------------

    @Test
    @DisplayName("only artists the owner actually follows are a reason")
    void shouldOnlyCountFollowedArtists() {
        Long followed = artist("Martin Scorsese", ArtistStatus.SEED);
        Long pending = artist("Leonardo DiCaprio", ArtistStatus.PENDING_REVIEW);
        Long rejected = artist("Jack Nicholson", ArtistStatus.REJECTED);
        Long removed = artist("Ray Liotta", ArtistStatus.REMOVED);
        Long approved = artist("Robert De Niro", ArtistStatus.APPROVED);
        Long goodfellas = work("Q101540", "Goodfellas", 1990);
        for (Long id : List.of(followed, pending, rejected, removed, approved)) {
            credit(id, goodfellas, "ACTED_IN");
        }

        assertThat(lookup(screening("Goodfellas", 1990)))
                .extracting(ScreeningCredit::artistName)
                .containsExactlyInAnyOrder("Martin Scorsese", "Robert De Niro");
    }

    @Test
    @DisplayName("one person's several roles on a film are one reason, not several")
    void shouldCollapseAPersonsRolesIntoOneReason() {
        Long scorsese = artist("Martin Scorsese", ArtistStatus.SEED);
        Long goodfellas = work("Q101540", "Goodfellas", 1990);
        credit(scorsese, goodfellas, "DIRECTED");
        credit(scorsese, goodfellas, "WROTE_SCREENPLAY_FOR");

        List<ScreeningCredit> found = lookup(screening("Goodfellas", 1990));
        assertThat(found).hasSize(1);
        assertThat(found.get(0).roles()).containsExactly("DIRECTED", "WROTE_SCREENPLAY_FOR");
    }

    @Test
    @DisplayName("a film nobody followed worked on is not a reason and not an error")
    void shouldReturnNothingForAFilmWithNoFollowedCredits() {
        work("Q101540", "Goodfellas", 1990);

        assertThat(lookup(screening("Goodfellas", 1990))).isEmpty();
    }

    @Test
    @DisplayName("a title with no work at all is simply absent from the result")
    void shouldReturnNothingForATitleWithNoWork() {
        assertThat(lookup(screening("Some Film Nobody Recorded", 2024))).isEmpty();
    }

    @Test
    @DisplayName("several screenings are answered in one call")
    void shouldAnswerSeveralScreeningsAtOnce() {
        Long scorsese = artist("Martin Scorsese", ArtistStatus.SEED);
        Long goodfellas = work("Q101540", "Goodfellas", 1990);
        Long departed = work("Q182218", "The Departed", 2006);
        credit(scorsese, goodfellas, "DIRECTED");
        credit(scorsese, departed, "DIRECTED");

        Map<ScreeningKey, List<ScreeningCredit>> found = credits.creditsFor(OWNER, List.of(
                screening("Goodfellas", 1990),
                screening("The Departed", 2006),
                screening("Unknown Film", 2020)));

        assertThat(found).hasSize(2);
        assertThat(found.get(screening("Goodfellas", 1990))).hasSize(1);
        assertThat(found.get(screening("The Departed", 2006))).hasSize(1);
    }

    @Test
    @DisplayName("another owner's catalog is not a reason")
    void shouldNotCrossOwners() {
        Long scorsese = artist("Martin Scorsese", ArtistStatus.SEED);
        Long goodfellas = work("Q101540", "Goodfellas", 1990);
        credit(scorsese, goodfellas, "DIRECTED");

        assertThat(credits.creditsFor("someone-else@example.com", List.of(screening("Goodfellas", 1990))))
                .isEmpty();
    }
}
