package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.catalog.VenuePerformerListener;
import com.robsartin.setlistscout.shared.JobStatus;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Real-path Testcontainers proof of #206 Task 4's wiring: the real {@link VenueScanRunner}
 * publishes a {@code VenuePerformerSeen} event per distinct scraped performer inside a committed
 * transaction (ADR-0024), and the real {@link VenuePerformerListener} (an {@code
 * @ApplicationModuleListener} in {@code catalog}) creates the PENDING_REVIEW/VENUE_EXPANSION
 * candidate.
 * <p>
 * Lives in {@code scan}, not {@code catalog}, unlike {@code catalog.RelationDiscoveredFlowTest}
 * (the equivalent flow test for expansion): {@link Venue}/{@link VenueScanJob}'s test-fixture
 * constructors are deliberately package-private (Tasks 1-2's own encapsulation -- "production code
 * creates rows exclusively through VenueRepository#insertIfAbsent's native INSERT, never {@code
 * new Venue(...)} + save()"), so only a test inside this package can build these fixtures at all;
 * {@code VenueScanJobRepository} has no public creator, and its entity's owner/venueId have no
 * setters. Everything on the {@code catalog} side used here for assertions
 * ({@code Artist}/{@code ArtistRepository}/{@code ArtistStatus}/{@code ArtistSource}) is public,
 * so importing them cross-package (as {@code VenueScanRunnerTest} already does for {@code
 * ArtistNameNormalizer}) is the only direction that actually works.
 * <p>
 * {@code VenuePerformerListenerTest} proves the LISTENER's own logic (dedup / status handling /
 * owner scoping) by calling it directly; it cannot, and does not try to, prove that {@code
 * VenueScanRunner}'s publish site actually commits and triggers that listener for real -- per
 * CLAUDE.md/ADR-0024, a test that never drives the actual production publisher is a false green
 * for "publish with no committing transaction is silently dropped." This class is that required
 * real-path test.
 */
@SpringBootTest
@Testcontainers
class VenuePerformerSeenFlowTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "venue-flow@example.com";
    private static final String CALENDAR_URL = "https://www.capcitycomedy.com/events";
    private static final LocalDateTime DATE_1 = LocalDateTime.now().plusDays(10);

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private VenueScanJobRepository venueScanJobRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private VenueScanRunner venueScanRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private BandSiteScraperService scraper;

    private VenueScanJob job;

    @BeforeEach
    void setUp() {
        // Container/context shared across test methods -- clear first, matching
        // VenueScanRunnerTest/RelationDiscoveredFlowTest's own setUp rationale.
        artistRepository.deleteAll();
        venueScanJobRepository.deleteAll();
        venueRepository.deleteAll();

        Venue venue = venueRepository.save(new Venue(OWNER, "Cap City Comedy Club",
                ArtistNameNormalizer.normalize("Cap City Comedy Club"), CALENDAR_URL));
        VenueScanJob fixture = new VenueScanJob(OWNER, venue.getId(), JobStatus.SCHEDULED, 0,
                Instant.now().minusSeconds(60));
        job = venueScanJobRepository.save(fixture);
    }

    @Test
    @DisplayName("a real venue scan publishes VenuePerformerSeen, and the real listener creates a "
            + "PENDING_REVIEW/VENUE_EXPANSION candidate")
    void venueScanCreatesRealCandidate() {
        when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of(
                new Show("Nick Mullen", DATE_1, "Cap City Comedy Club", "Austin",
                        null, "x", "u", Show.Kind.COMEDY)));

        venueScanRunner.run(job);

        Artist created = awaitUntil(
                () -> artistRepository.findByOwnerAndName(OWNER, "Nick Mullen").orElse(null),
                a -> a != null);
        assertThat(created).as("candidate persisted via the real publish -> listener path").isNotNull();
        assertThat(created.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(created.getSource()).isEqualTo(ArtistSource.VENUE_EXPANSION);
    }

    /**
     * Closes a blind spot {@code VenuePerformerListenerTest} cannot: a direct {@code
     * listener.on(...)} call proved the listener's OWN no-branching logic is correct, but a
     * mutation test during that class's own development (see its class doc) showed that a
     * same-count-either-way negative assertion alone cannot tell "cleanly no-op'd" from "an
     * insert threw and got silently swallowed" -- both leave the row count and status unchanged.
     * This test closes that gap two ways at once: a "Control Comedian" performer in the SAME
     * scrape result is a positive control (see setlist-scout's own flake lesson: empty results and
     * a dead instrument look identical) proving the publish -> listener path is genuinely alive in
     * THIS run, not just idle; and {@link #awaitQuiescence} waits on {@code
     * event_publication.completion_date}, which Modulith stamps only on a listener's SUCCESSFUL
     * return -- if Nick Mullen's specific publication were instead thrown/poisoned, this would
     * time out rather than false-pass alongside the (correctly landed) control.
     */
    @Test
    @DisplayName("#206: a rejected performer stays rejected across repeated real venue scans")
    void rejectedPerformerStaysRejectedAcrossRealRescans() {
        Artist rejected = new Artist("Nick Mullen", ArtistSource.VENUE_EXPANSION, ArtistStatus.REJECTED, null, null);
        rejected.setOwner(OWNER);
        artistRepository.save(rejected);

        when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of(
                new Show("Nick Mullen", DATE_1, "Cap City Comedy Club", "Austin",
                        null, "x", "u", Show.Kind.COMEDY),
                new Show("Control Comedian", DATE_1, "Cap City Comedy Club", "Austin",
                        null, "x", "u", Show.Kind.COMEDY)));

        // Simulates two real scan cycles re-listing the same performer -- the exact production
        // scenario #206's context calls out: a venue calendar re-lists the same comedian every
        // scan, so a rejection must survive repeated real runs, not just one.
        venueScanRunner.run(job);
        venueScanRunner.run(job);

        // Positive control first -- see this test's own doc.
        awaitUntil(() -> artistRepository.findByOwnerAndName(OWNER, "Control Comedian").orElse(null),
                a -> a != null);
        // Then wait for every triggered listener (including Nick Mullen's own publication, a
        // separate event_publication row from the control's) to have actually finished -- and,
        // critically, ASSERT on the returned incomplete-count rather than just calling this for
        // its side effect. awaitQuiescence returns the LAST fetched count regardless of whether
        // it ever reached zero (by design, so a timeout still fails with a useful number instead
        // of a bare "empty"); a bare statement call here would make this wait purely cosmetic --
        // proven concretely, not assumed: a mutation that made the listener throw instead of
        // cleanly no-op (see VenuePerformerListenerTest's class doc) still left this test GREEN
        // when this line was a bare statement, because Postgres's own unique-constraint rollback
        // happens to leave the SAME visible row count/status as a correct ON CONFLICT DO NOTHING
        // no-op -- the only externally-observable difference is that the listener's transaction
        // never completes, which only this assertion, not the end-state checks below, can see.
        Long incomplete = awaitQuiescence(jdbcTemplate);
        assertThat(incomplete).as("every listener triggered by this run's two scans finished "
                + "cleanly -- none left stuck retrying/failing").isZero();

        List<Artist> all = artistRepository.findByOwner(OWNER);
        assertThat(all).extracting(Artist::getName)
                .as("still just the original rejected row plus the one new control -- no duplicate "
                        + "Nick Mullen row from either scan")
                .containsExactlyInAnyOrder("Nick Mullen", "Control Comedian");
        Artist stillRejected = all.stream()
                .filter(a -> a.getName().equals("Nick Mullen"))
                .findFirst().orElseThrow();
        assertThat(stillRejected.getStatus())
                .as("stays REJECTED across repeated real scans -- not resurrected to PENDING_REVIEW")
                .isEqualTo(ArtistStatus.REJECTED);
    }
}
