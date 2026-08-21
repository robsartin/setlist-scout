package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.ShowRepository;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real / (shows) Thymeleaf template against a booted context + Postgres, signed in
 * as a test user. Checks that the migrated shows.html carries the shared layout (nav + app.css)
 * on the full page, but that the htmx swap fragment stays bare (no page chrome). Uses an owner
 * not in seed-bands.txt so seeded data doesn't leak into this test. Runs in CI (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ShowsPageRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "render-layout@example.com";

    /**
     * Comfortably inside every owner's default 6-month search window
     * ({@code application.yml}'s {@code months-ahead} default) without being borderline near a
     * DST/month-boundary edge -- these tests don't exercise the window itself, so this only needs
     * to be "clearly future, clearly in range."
     */
    private static final LocalDateTime FUTURE = LocalDateTime.now().plusDays(30);

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Test
    void showsPageRendersWithNavAndStylesheet() throws Exception {
        mvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("/css/app.css")))
                .andExpect(content().string(containsString("aria-current=\"page\"")))   // Shows is current
                .andExpect(content().string(containsString(">Artists<")))               // nav link present
                .andExpect(content().string(containsString("id=\"shows-region\"")))     // htmx region preserved
                .andExpect(content().string(containsString("/settings")));              // settings form preserved
    }

    @Test
    void scanNowHtmxReturnsBareShowsRegionFragment() throws Exception {
        var res = mvc.perform(post("/scan-now").header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        // htmx fragment must be JUST the region -- no full-page chrome:
        assertThat(res).contains("shows-region");
        assertThat(res).doesNotContain("<head").doesNotContain("topbar");
    }

    // #206 Task 5: the active-artist display filter. Each test below uses its OWN owner (never
    // the shared OWNER constant above) -- artist (owner, normalized_name) and show_event
    // (owner, artist_name, event_date_time, venue_name) are both real unique constraints, and
    // several of these tests seed a row for "Nick Mullen" with a DIFFERENT status/casing than a
    // sibling test does; sharing one owner across them would make seeding order-dependent
    // (whichever test's insert lands second throws a DataIntegrityViolationException, not a clean
    // assertion failure). Distinct owners keep every test independent, matching this package's own
    // convention (see web.CandidateActionsTest's per-test-method owner strings).

    /**
     * TicketmasterService stores the EVENT TITLE in {@code artist_name} (label is the event name,
     * falling back to the artist name only when blank) -- see {@code ShowController#populateShows}.
     * "A Very Merry Symphony ft. Austin Symphony Orchestra" is a real show in the owner's
     * production data today, and that string is not, and never will be, a catalog artist name.
     * <p>
     * This is a deliberate PINNING test, not a standard red-first case: it is expected, and
     * required, to pass both BEFORE and AFTER the active-artist filter lands, because the filter
     * must apply only to {@code venue:}-prefixed sources. If this ever goes red, the filter has
     * become a blanket one and just hid every Ticketmaster show the owner has (48 of them, at time
     * of writing) -- the exact production bug this task exists to avoid.
     */
    @Test
    @DisplayName("a Ticketmaster show whose artist_name is an event title still displays")
    void ticketmasterEventTitleShowStillDisplays() throws Exception {
        String owner = "venue-filter-ticketmaster@example.com";
        seedShow(owner, "A Very Merry Symphony ft. Austin Symphony Orchestra", "ticketmaster", FUTURE);

        mvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("A Very Merry Symphony")));
    }

    @Test
    @DisplayName("a venue show whose performer is not an active artist is not displayed")
    void venueShowForUnfollowedPerformerIsHidden() throws Exception {
        String owner = "venue-filter-unfollowed@example.com";
        seedShow(owner, "Nick Mullen", "venue:www.capcitycomedy.com", FUTURE);

        mvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(content().string(not(containsString("Nick Mullen"))));
    }

    @Test
    @DisplayName("a venue show whose performer IS an active artist is displayed")
    void venueShowForFollowedPerformerIsShown() throws Exception {
        String owner = "venue-filter-followed@example.com";
        seedArtist(owner, "Nick Mullen", ArtistStatus.APPROVED, ArtistSource.VENUE_EXPANSION);
        seedShow(owner, "Nick Mullen", "venue:www.capcitycomedy.com", FUTURE);

        mvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(content().string(containsString("Nick Mullen")));
    }

    @Test
    @DisplayName("venue-show matching is case-insensitive, like all name equality here")
    void venueShowMatchingUsesNormalizedNames() throws Exception {
        String owner = "venue-filter-normalized@example.com";
        seedArtist(owner, "Nick Mullen", ArtistStatus.APPROVED, ArtistSource.VENUE_EXPANSION);
        seedShow(owner, "nick mullen", "venue:www.capcitycomedy.com", FUTURE);

        mvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(content().string(containsString("nick mullen")));
    }

    /**
     * Closes a real gap left by {@link #venueShowMatchingUsesNormalizedNames}: that test only
     * varies casing, which a plain {@code toLowerCase(Locale.ROOT)} would also satisfy -- it
     * cannot, by itself, prove {@code ArtistNameNormalizer.normalize} (rather than a hand-rolled
     * lowercase) is what's actually doing the matching. This test varies hyphen-spacing instead
     * (the normalizer's own documented issue #157 case: {@code "X - Y"} and {@code "X-Y"} match),
     * which a plain lowercase does NOT fold -- see the mutation-testing trail in the task-5 report
     * for empirical confirmation this test goes red under a toLowerCase-only implementation while
     * {@link #venueShowMatchingUsesNormalizedNames} stays green.
     */
    @Test
    @DisplayName("venue-show matching folds hyphen-spacing too, proving ArtistNameNormalizer is used (not a plain lowercase)")
    void venueShowMatchingFoldsHyphenSpacingLikeArtistNameNormalizer() throws Exception {
        String owner = "venue-filter-hyphen@example.com";
        seedArtist(owner, "Jane Doe-Smith", ArtistStatus.APPROVED, ArtistSource.VENUE_EXPANSION);
        seedShow(owner, "Jane Doe - Smith", "venue:www.capcitycomedy.com", FUTURE);

        mvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(content().string(containsString("Jane Doe - Smith")));
    }

    @Test
    @DisplayName("a PENDING_REVIEW performer's venue shows are not displayed until approved")
    void pendingPerformerShowsAreHidden() throws Exception {
        String owner = "venue-filter-pending@example.com";
        seedArtist(owner, "Nick Mullen", ArtistStatus.PENDING_REVIEW, ArtistSource.VENUE_EXPANSION);
        seedShow(owner, "Nick Mullen", "venue:www.capcitycomedy.com", FUTURE);

        mvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(content().string(not(containsString("Nick Mullen"))));
    }

    private void seedShow(String owner, String artistName, String source, LocalDateTime eventDateTime) {
        Show show = new Show(artistName, eventDateTime, "Test Venue", "Austin",
                null, source, null, Show.Kind.MUSIC);
        show.setOwner(owner);
        showRepository.save(show);
    }

    private void seedArtist(String owner, String name, ArtistStatus status, ArtistSource source) {
        Artist artist = new Artist(name, source, status, null, null);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }
}
