package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.ShowRepository;
import com.robsartin.setlistscout.scan.VenueRepository;
import com.robsartin.setlistscout.scan.VenueScanJob;
import com.robsartin.setlistscout.scan.VenueScanJobRepository;
import com.robsartin.setlistscout.scan.VenueService;
import com.robsartin.setlistscout.shared.JobStatus;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Renders the real {@code /venues} Thymeleaf template against a booted context + Postgres,
 * signed in as a test user (#206 Task 6). This page is the ONLY way an owner adds a venue outside
 * a test -- it is what makes the whole follow-a-venue feature reachable.
 * <p>
 * Login mechanism corrected vs. the task brief's given snippets, same lesson Task 5's own report
 * documents: {@code CurrentUser#email()} reads {@code OidcUser#getEmail()} specifically, so
 * Spring Security's plain {@code user(OWNER)} post-processor (which never populates an
 * {@code OidcUser} principal) would leave every seeded row invisible. Uses
 * {@code oidcLogin().idToken(t -> t.claim("email", owner))} instead, matching
 * {@code ShowsPageRenderTest}/{@code ArtistPageRenderTest}. {@code /venues} itself IS the correct
 * route here (unlike Task 5's {@code /shows} correction) -- this controller defines that mapping.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class VenuePageRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * Shared by the two purely-structural tests below that seed no venue-specific content --
     * mirrors {@code ShowsPageRenderTest}'s own convention. Every content-seeding test below gets
     * its OWN owner instead: {@code venue (owner, normalized_name)} is a real unique constraint,
     * and JUnit 5's default test order is unspecified, so sharing one owner across content-bearing
     * tests would make seeding order-dependent (see the same lesson in ShowsPageRenderTest,
     * traced back to Task 5's report).
     */
    private static final String OWNER = "venues-render-layout@example.com";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private VenueScanJobRepository venueScanJobRepository;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private VenueService venueService;

    @Test
    @DisplayName("renders the add form above the venue list")
    void rendersAddFormAboveList() throws Exception {
        String html = mvc.perform(get("/venues").with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        int addIdx = html.indexOf("id=\"add-venue\"");
        int listIdx = html.indexOf("id=\"venue-list\"");
        assertThat(addIdx).isPositive();
        assertThat(listIdx).isPositive();
        assertThat(addIdx).isLessThan(listIdx);
    }

    @Test
    @DisplayName("both inputs are labelled")
    void inputsAreLabelled() throws Exception {
        String html = mvc.perform(get("/venues").with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andReturn().getResponse().getContentAsString();
        assertThat(html).contains("for=\"venue-name\"").contains("for=\"venue-url\"");
    }

    @Test
    @DisplayName("shows last-scanned and contributed-show count for a venue that has been scanned")
    void showsScanHealth() throws Exception {
        String owner = "venues-scan-health@example.com";
        venueService.addVenue(owner, "Cap City Comedy Club", "https://www.capcitycomedy.com/events");
        VenueScanJob job = venueScanJobRepository.findByOwner(owner).get(0);
        job.setLastRunAt(Instant.parse("2026-08-01T12:00:00Z"));
        venueScanJobRepository.save(job);
        seedShow(owner, "Nick Mullen", "venue:www.capcitycomedy.com", LocalDateTime.now().plusDays(10));

        String html = mvc.perform(get("/venues").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("1 show");
        assertThat(html).doesNotContain("Never scanned");
    }

    /**
     * Closes a real gap in the brief's own given test: "1 show" alone cannot prove last-scanned
     * and show-count are tracked per venue rather than as some sitewide total, and cannot prove a
     * venue that has never run is told apart from one that ran and found nothing. That distinction
     * is the brief's own stated point: "a venue whose calendar silently stops parsing looks
     * identical to a venue with no shows, which is exactly how #211 hid." A freshly-added venue
     * (job created, never claimed/run yet) must read differently from a scanned-but-empty one, or
     * this page cannot actually surface a silently-broken scan.
     */
    @Test
    @DisplayName("a venue that has never been scanned reads differently from one with zero shows")
    void neverScannedVenueIsDistinguishableFromZeroShows() throws Exception {
        String owner = "venues-never-scanned@example.com";
        venueService.addVenue(owner, "Empty Room", "https://empty.example.com/events");

        String html = mvc.perform(get("/venues").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).contains("Never scanned");
        assertThat(html).contains("0 shows");
    }

    @Test
    @DisplayName("adding a venue creates it and its scan job")
    void addingVenueCreatesScanJob() throws Exception {
        String owner = "venues-add@example.com";
        mvc.perform(post("/venues").with(oidcLogin().idToken(t -> t.claim("email", owner))).with(csrf())
                .param("name", "Cap City Comedy Club")
                .param("url", "https://www.capcitycomedy.com/events"));

        assertThat(venueRepository.findByOwnerOrderByNameAsc(owner)).hasSize(1);
        // Scoped by owner, not the brief's bare findAll(): other tests in this class create their
        // own venue_scan_job rows under their own owners, so a global count would be
        // order-dependent -- see this task's report for why the given snippet was changed.
        List<VenueScanJob> jobs = venueScanJobRepository.findByOwner(owner);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(jobs.get(0).getVenueId()).isEqualTo(venueRepository.findByOwnerOrderByNameAsc(owner).get(0).getId());
    }

    @Test
    @DisplayName("one owner never sees another's venues")
    void isOwnerScoped() throws Exception {
        // NOT the brief's own "Someone Else's Room" -- Thymeleaf HTML-escapes the apostrophe to
        // "&#39;", so a literal-apostrophe substring check against the rendered page is vacuously
        // true whether or not owner-scoping actually works. Caught empirically: mutation-testing
        // this test (VenueController#rows changed to venueRepository.findAll(), owner scoping
        // dropped entirely) left THIS test green -- see this task's report. An apostrophe-free
        // name makes the substring check mean what it says.
        venueService.addVenue("venues-other-owner@example.com", "Other Owner Venue", "https://x.example.com/events");

        String owner = "venues-scoped-viewer@example.com";
        String html = mvc.perform(get("/venues").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(html).doesNotContain("Other Owner Venue");
    }

    /**
     * Mutation-guard for the per-venue GROUP BY (see this task's report): with only ONE venue per
     * owner, a controller that summed ALL of an owner's venue-sourced shows into every row (instead
     * of matching each venue's own computed source key) would still pass every test above. Two
     * venues under the SAME owner with DIFFERENT show counts is what actually proves the counts
     * are per-venue, not conflated.
     */
    @Test
    @DisplayName("each venue's contributed-show count is its own, not conflated with another venue's")
    void showCountsAreNotConflatedAcrossVenues() throws Exception {
        String owner = "venues-two-venues@example.com";
        venueService.addVenue(owner, "Busy Room", "https://busy.example.com/events");
        venueService.addVenue(owner, "Quiet Room", "https://quiet.example.com/events");
        seedShow(owner, "Band A", "venue:busy.example.com", LocalDateTime.now().plusDays(5));
        seedShow(owner, "Band B", "venue:busy.example.com", LocalDateTime.now().plusDays(6));
        // Quiet Room deliberately gets no shows.

        String html = mvc.perform(get("/venues").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        int busyIdx = html.indexOf("Busy Room");
        int quietIdx = html.indexOf("Quiet Room");
        assertThat(busyIdx).isPositive();
        assertThat(quietIdx).isPositive();
        // findByOwnerOrderByNameAsc sorts alphabetically, so "Busy Room" precedes "Quiet Room" --
        // slice each row's own region and assert its count within that slice, not just anywhere
        // on the page.
        String busyRow = html.substring(busyIdx, quietIdx);
        String quietRow = html.substring(quietIdx);
        assertThat(busyRow).contains("2 shows");
        assertThat(quietRow).contains("0 shows");
    }

    private void seedShow(String owner, String artistName, String source, LocalDateTime eventDateTime) {
        Show show = new Show(artistName, eventDateTime, "Test Venue", "Austin",
                null, source, null, Show.Kind.MUSIC);
        show.setOwner(owner);
        showRepository.save(show);
    }
}
