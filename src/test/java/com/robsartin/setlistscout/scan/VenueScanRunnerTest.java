package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Testcontainers-backed TDD for {@link VenueScanRunner} (#206 Task 3): scrape -> persist ->
 * record outcome on the {@link VenueScanJob} itself, against a real Postgres -- the idempotency
 * test in particular (rescanning must not duplicate) exercises {@code show_event}'s real unique
 * index via {@code ON CONFLICT DO NOTHING}, which a mock-only test can't prove. Mirrors
 * {@code VenueRepositoryTest}/{@code VenueScanJobRepositoryTest}'s shape (#206 Tasks 1-2): no
 * {@code @Transactional} on test methods (a shared Hibernate persistence context would return a
 * stale pre-run object instead of the post-run DB row -- see those tasks' reports), fixtures via
 * each entity's package-private test constructor + plain {@code save()}.
 * <p>
 * Only {@link BandSiteScraperService} is mocked (a leaf HTTP/LLM adapter) -- everything from the
 * runner down to the database is the real production wiring, per CLAUDE.md's "a Modulith Scenario
 * test is a false green" rule generalized: prove the real persistence path, not a stand-in.
 * {@code setlistscout.venue-poller-enabled=false} in {@code application.properties} keeps the
 * real, live {@link VenueScanPoller} bean from firing in the background during this test and
 * racing the same {@link #scraper} mock / the same {@code job} row -- the exact class of flake
 * {@code PollerFlowTest} documents for {@code scan-poller-enabled}/{@code expand-poller-enabled}.
 */
@SpringBootTest
@Testcontainers
class VenueScanRunnerTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "rob@example.com";
    private static final String CALENDAR_URL = "https://www.capcitycomedy.com/events";
    private static final LocalDateTime DATE_1 = LocalDateTime.now().plusDays(10);
    private static final LocalDateTime DATE_2 = LocalDateTime.now().plusDays(17);

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private VenueScanJobRepository venueScanJobRepository;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private VenueScanRunner runner;

    @MockitoBean
    private BandSiteScraperService scraper;

    private VenueScanJob job;

    @BeforeEach
    void setUp() {
        showRepository.deleteAll();
        venueScanJobRepository.deleteAll();
        venueRepository.deleteAll();

        Venue venue = venueRepository.save(new Venue(OWNER, "Cap City Comedy Club",
                ArtistNameNormalizer.normalize("Cap City Comedy Club"), CALENDAR_URL));

        VenueScanJob fixture = new VenueScanJob(OWNER, venue.getId(), JobStatus.SCHEDULED, 0,
                Instant.now().minusSeconds(60));
        // A poller claim sets claimed_at without touching status (VenueScanJobRepository#claimDue,
        // #206 Task 2) -- seeding a non-null claimed_at here mirrors that claimed-and-running state,
        // so "claimedAt is null after run()" below actually proves the runner released it rather
        // than trivially observing a value that was already null.
        fixture.setClaimedAt(Instant.now());
        job = venueScanJobRepository.save(fixture);
    }

    @Test
    @DisplayName("persists every extracted show with the performer as artist_name and a venue: source")
    void persistsEveryExtractedShow() {
        when(scraper.scrapeShows(eq("Cap City Comedy Club"), eq(CALENDAR_URL), any(), any()))
                .thenReturn(List.of(
                        new Show("Matt Braunger", DATE_1, "The Red Room at Cap City", "Austin",
                                null, "x", "u", Show.Kind.COMEDY),
                        new Show("Nick Mullen", DATE_2, "Cap City Comedy Club", "Austin",
                                null, "x", "u", Show.Kind.COMEDY)));

        runner.run(job);

        List<Show> stored = showRepository.findByOwnerOrderByEventDateTimeAsc(OWNER);
        assertThat(stored).extracting(Show::getArtistName)
                .containsExactlyInAnyOrder("Matt Braunger", "Nick Mullen");
        assertThat(stored).allSatisfy(s -> assertThat(s.getSource()).startsWith("venue:"));
        assertThat(stored).extracting(Show::getKind).containsOnly(Show.Kind.COMEDY);
    }

    @Test
    @DisplayName("keeps the room name the extractor reported, not the venue's own name")
    void keepsExtractedRoomName() {
        when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of(
                new Show("Matt Braunger", DATE_1, "The Red Room at Cap City", "Austin",
                        null, "x", "u", Show.Kind.COMEDY)));
        runner.run(job);
        assertThat(showRepository.findByOwnerOrderByEventDateTimeAsc(OWNER))
                .singleElement()
                .extracting(Show::getVenueName).isEqualTo("The Red Room at Cap City");
    }

    @Test
    @DisplayName("a scraper failure marks the job failed and does not break the run")
    void recordsScraperFailure() {
        when(scraper.scrapeShows(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));
        runner.run(job);
        VenueScanJob reloaded = venueScanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getLastError()).contains("boom");
        assertThat(reloaded.getClaimedAt()).isNull();
    }

    @Test
    @DisplayName("rescanning the same calendar does not duplicate shows")
    void rescanIsIdempotent() {
        when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of(
                new Show("Matt Braunger", DATE_1, "Cap City Comedy Club", "Austin",
                        null, "x", "u", Show.Kind.COMEDY)));
        runner.run(job);
        runner.run(job);
        assertThat(showRepository.findByOwnerOrderByEventDateTimeAsc(OWNER)).hasSize(1);
    }

    /**
     * Beyond the brief's four given tests: closes a blind spot in {@code rescanIsIdempotent} above.
     * That test's {@code hasSize(1)} assertion alone can't distinguish "the second run inserted
     * nothing because ON CONFLICT DO NOTHING suppressed a real duplicate" from "the second run's
     * insert threw a constraint violation that {@code run()} silently caught as a recorded
     * failure" -- both leave exactly one row. A rescan that quietly fails every time (instead of
     * cleanly no-op'ing) would still pass that test. This asserts the second run left no error and
     * reset attempts to 0, which only the true idempotent-no-op path produces.
     */
    @Test
    @DisplayName("rescanning the same calendar succeeds cleanly -- it does not silently record a failure")
    void rescanSucceedsCleanlyNotViaCaughtFailure() {
        when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of(
                new Show("Matt Braunger", DATE_1, "Cap City Comedy Club", "Austin",
                        null, "x", "u", Show.Kind.COMEDY)));
        runner.run(job);
        runner.run(job);
        VenueScanJob reloaded = venueScanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getLastError()).isNull();
        assertThat(reloaded.getAttempts()).isZero();
    }

    /**
     * Beyond the brief's four given tests: the only coverage anywhere of the success-path job
     * bookkeeping Step 3 specifies (status/claimedAt/lastRunAt/nextDueAt/attempts). Starts
     * {@code attempts} non-zero specifically to prove the reset-to-0 branch, not just "stayed 0".
     */
    @Test
    @DisplayName("a successful run reschedules the job: SCHEDULED, released, attempts reset, next due later")
    void successReschedulesJob() {
        job.setAttempts(3);
        job = venueScanJobRepository.save(job);
        when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of());

        Instant before = Instant.now();
        runner.run(job);
        Instant after = Instant.now();

        VenueScanJob reloaded = venueScanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(reloaded.getClaimedAt()).isNull();
        assertThat(reloaded.getAttempts()).isZero();
        assertThat(reloaded.getLastRunAt()).isBetween(before, after);
        // Whatever the configured venue-scan-interval is, a fresh reschedule must land well after
        // "now" -- this doesn't hardcode the interval's value, just that it moved forward for real.
        assertThat(reloaded.getNextDueAt()).isAfter(after.plusSeconds(60));
    }

    /**
     * Beyond the brief's four given tests: pins the subtlety that makes a failed venue job
     * reclaimable again, unlike {@code scan_job}. {@code VenueScanJobRepository#claimDue} (#206
     * Task 2, already committed) only ever selects {@code status = 'SCHEDULED'} rows and never
     * changes {@code status} itself on claim -- so if a failed run set {@code status = FAILED} the
     * way {@code ScanPoller.recordFailure} does for {@code scan_job}, that row could never be
     * claimed again. This proves the runner leaves {@code status} alone on failure.
     */
    @Test
    @DisplayName("a scraper failure leaves the job SCHEDULED so claimDue can reclaim it")
    void failureKeepsJobReclaimable() {
        when(scraper.scrapeShows(any(), any(), any(), any())).thenThrow(new RuntimeException("boom"));

        runner.run(job);

        VenueScanJob reloaded = venueScanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(reloaded.getAttempts()).isEqualTo(1);
        // Backoff must be a near-term retry, not the same far-future cadence a success gets --
        // otherwise a transient failure would wait as long as a normal recheck.
        assertThat(reloaded.getNextDueAt()).isBefore(Instant.now().plus(Duration.ofHours(1)));
    }
}
