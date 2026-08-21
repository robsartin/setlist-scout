package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSeedService;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJob;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.service.TestAppProperties;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.AdminGuard;
import com.robsartin.setlistscout.shared.CurrentUser;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.ui.ConcurrentModel;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-path Testcontainers proof of issue #223's Part B: {@code POST /shows/{id}/hide-and-cancel}
 * driven through the REAL {@link ArtistActivationService} and the real {@code
 * scan.ScanJobListener}/{@code expansion.ExpandJobListener} {@code @ApplicationModuleListener}s (not
 * mocks), so a green result actually proves the activation event fired and its listeners' cleanup
 * ran -- a Modulith {@code Scenario} test would be a false green per ADR-0024. Mirrors {@link
 * com.robsartin.setlistscout.catalog.RemoveFromSeedFlowTest}'s and {@link
 * VenuePerformerSeenFlowTest}'s style: autowire the real collaborators, construct {@link
 * ShowController} directly with a stubbed {@link CurrentUser} so each test controls the acting
 * owner without standing up MockMvc/security.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false",
        "setlistscout.venue-poller-enabled=false"
})
class HideAndCancelFlowTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "hide-and-cancel-flow@example.com";

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ArtistSeedService artistSeedService;

    @Autowired
    private ArtistActivationService artistActivationService;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private ScanJobRepository scanJobRepository;

    @Autowired
    private ExpandJobRepository expandJobRepository;

    @Autowired
    private List<ShowSource> showSources;

    @Autowired
    private List<RelationSource> relationSources;

    @Autowired
    private VenueRepository venueRepository;

    @Autowired
    private VenueScanJobRepository venueScanJobRepository;

    @Autowired
    private VenueScanRunner venueScanRunner;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private BandSiteScraperService scraper;

    private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        // Shared container/context across this class's test methods -- clear first, matching
        // RemoveFromSeedFlowTest/VenuePerformerSeenFlowTest's own setUp rationale.
        showRepository.deleteAll();
        artistRepository.deleteAll();
        venueScanJobRepository.deleteAll();
        venueRepository.deleteAll();
        currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
    }

    private ShowController controller() {
        AdminGuard adminGuard = new AdminGuard(currentUser, TestAppProperties.withKeys());
        return new ShowController(showRepository, artistRepository, scanJobRepository,
                settingsService, currentUser, adminGuard, artistActivationService);
    }

    private Long seedArtist(String name) {
        assertThat(artistSeedService.addSeedIfNew(OWNER, name)).isTrue();
        return artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.SEED).stream()
                .filter(a -> a.getName().equals(name))
                .findFirst().orElseThrow().getId();
    }

    private Long saveShowFor(String owner, Long artistId, String artistName, LocalDateTime when, String venue) {
        Show show = new Show(artistName, when, venue, "Austin", BigDecimal.TEN, "ticketmaster", null, Show.Kind.MUSIC);
        show.setOwner(owner);
        show.setArtistId(artistId);
        return showRepository.save(show).getId();
    }

    @Test
    @DisplayName("issue #223: hide-and-cancel on a SEED artist's show hides it, transitions the "
            + "artist to REMOVED through the REAL ArtistActivationService, and the real "
            + "ScanJobListener/ExpandJobListener cancel its scan_job/expand_job rows -- proving the "
            + "activation event fired, not just that the status column changed")
    void hideAndCancelOnSeedArtistCancelsRealJobs() {
        Long artistId = seedArtist("Flow Seed Artist");
        // Confirm the SEED artist really has live jobs before acting -- otherwise "rows are gone"
        // afterward would be trivially true because they never existed.
        awaitUntil(() -> scanJobRepository.findByOwnerAndArtistId(OWNER, artistId),
                jobs -> jobs.size() == showSources.size());
        awaitUntil(() -> expandJobRepository.findByOwnerAndArtistId(OWNER, artistId),
                jobs -> jobs.size() == relationSources.size());

        LocalDateTime when = LocalDateTime.now().plusDays(10).truncatedTo(ChronoUnit.SECONDS);
        Long showId = saveShowFor(OWNER, artistId, "Flow Seed Artist", when, "Moody Center");

        String view = controller().hideAndCancel(showId, "eventDate", false, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/");
        assertThat(showRepository.findById(showId).orElseThrow().getHiddenAt()).isNotNull();
        Artist removed = artistRepository.findByIdAndOwner(artistId, OWNER).orElseThrow();
        assertThat(removed.getStatus()).isEqualTo(ArtistStatus.REMOVED);

        List<ScanJob> remainingScanJobs = awaitUntil(
                () -> scanJobRepository.findByOwnerAndArtistId(OWNER, artistId), List::isEmpty);
        List<ExpandJob> remainingExpandJobs = awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(OWNER, artistId), List::isEmpty);
        assertThat(remainingScanJobs).as("scan_job rows cancelled by the real listener").isEmpty();
        assertThat(remainingExpandJobs).as("expand_job rows cancelled by the real listener").isEmpty();
    }

    @Test
    @DisplayName("issue #223: hide-and-cancel on an APPROVED (expansion-sourced) artist's show "
            + "transitions it to REJECTED and cancels its real jobs the same way")
    void hideAndCancelOnApprovedArtistCancelsRealJobs() {
        // A real PENDING_REVIEW -> APPROVED transition through ArtistActivationService, exactly
        // like approving a real expansion candidate -- ArtistActivated fires and real jobs enqueue.
        Artist candidate = new Artist("Flow Approved Artist", ArtistSource.MEMBER_EXPANSION,
                ArtistStatus.PENDING_REVIEW, "Some Seed", null);
        candidate.setOwner(OWNER);
        Long artistId = artistRepository.save(candidate).getId();
        artistActivationService.changeStatus(artistId, OWNER, ArtistStatus.APPROVED);
        awaitUntil(() -> scanJobRepository.findByOwnerAndArtistId(OWNER, artistId),
                jobs -> jobs.size() == showSources.size());
        awaitUntil(() -> expandJobRepository.findByOwnerAndArtistId(OWNER, artistId),
                jobs -> jobs.size() == relationSources.size());

        LocalDateTime when = LocalDateTime.now().plusDays(11).truncatedTo(ChronoUnit.SECONDS);
        Long showId = saveShowFor(OWNER, artistId, "Flow Approved Artist", when, "ACL Live");

        controller().hideAndCancel(showId, "eventDate", false, null, new ConcurrentModel());

        Artist rejected = artistRepository.findByIdAndOwner(artistId, OWNER).orElseThrow();
        assertThat(rejected.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        List<ScanJob> remainingScanJobs = awaitUntil(
                () -> scanJobRepository.findByOwnerAndArtistId(OWNER, artistId), List::isEmpty);
        List<ExpandJob> remainingExpandJobs = awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(OWNER, artistId), List::isEmpty);
        assertThat(remainingScanJobs).as("scan_job rows cancelled by the real listener").isEmpty();
        assertThat(remainingExpandJobs).as("expand_job rows cancelled by the real listener").isEmpty();
    }

    @Test
    @DisplayName("issue #223: a null artist_id hides the show for real, through the real controller "
            + "path, and touches no artist")
    void hideAndCancelWithNullArtistIdOnlyHidesTheShowForReal() {
        LocalDateTime when = LocalDateTime.now().plusDays(9).truncatedTo(ChronoUnit.SECONDS);
        Show show = new Show("A Very Merry Symphony ft. Austin Symphony Orchestra", when, "Long Center",
                "Austin", BigDecimal.TEN, "ticketmaster", null, Show.Kind.MUSIC);
        show.setOwner(OWNER);
        Long showId = showRepository.save(show).getId();

        String view = controller().hideAndCancel(showId, "eventDate", false, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/");
        assertThat(showRepository.findById(showId).orElseThrow().getHiddenAt()).isNotNull();
        assertThat(artistRepository.findByOwner(OWNER)).as("no artist row was created or touched").isEmpty();
    }

    @Test
    @DisplayName("issue #223: hide-and-cancel never touches another owner's show or artist")
    void hideAndCancelIsOwnerScoped() {
        String otherOwner = "hide-and-cancel-other-owner@example.com";
        Artist theirArtist = new Artist("Their Artist", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        theirArtist.setOwner(otherOwner);
        Long theirArtistId = artistRepository.save(theirArtist).getId();
        LocalDateTime when = LocalDateTime.now().plusDays(5).truncatedTo(ChronoUnit.SECONDS);
        Long theirShowId = saveShowFor(otherOwner, theirArtistId, "Their Artist", when, "Their Venue");

        String view = controller().hideAndCancel(theirShowId, "eventDate", false, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/");
        assertThat(showRepository.findById(theirShowId).orElseThrow().getHiddenAt())
                .as("a foreign show id is a no-op, not a leak").isNull();
        assertThat(artistRepository.findById(theirArtistId).orElseThrow().getStatus())
                .as("the other owner's artist is untouched").isEqualTo(ArtistStatus.SEED);
    }

    /**
     * Closes the loop between Part A and Part B end to end: {@link VenueScanRunner}'s own
     * name-resolved {@code artist_id} (issue #223, Part A) is what lets THIS button do anything
     * useful for a venue-sourced show in the first place, and {@code
     * catalog.VenuePerformerListener}'s existing {@code ON CONFLICT DO NOTHING} no-op (#206) is what
     * keeps the rejection from being undone by the very next scan. Mirrors {@code
     * VenuePerformerSeenFlowTest#rejectedPerformerStaysRejectedAcrossRealRescans}'s positive-control
     * + {@link #awaitQuiescence} technique: a same-scrape "Control Comedian" proves the publish -&gt;
     * listener path is genuinely alive in this run (not just idle), and waiting on {@code
     * event_publication} completion -- not just re-checking row counts -- distinguishes a clean
     * no-op from a listener that quietly failed and left the same visible state (see that test's own
     * doc for the mutation that motivated this).
     */
    @Test
    @DisplayName("issue #223 + #206: a rejected venue performer is not re-created by the next real venue scan")
    void rejectedVenuePerformerStaysRejectedAcrossRealVenueRescans() {
        Artist performer = new Artist("Flow Comedian", ArtistSource.VENUE_EXPANSION,
                ArtistStatus.PENDING_REVIEW, null, null);
        performer.setOwner(OWNER);
        Long performerId = artistRepository.save(performer).getId();

        Venue venue = venueRepository.save(new Venue(OWNER, "Flow Comedy Club",
                ArtistNameNormalizer.normalize("Flow Comedy Club"), "https://flow-comedy.example/events"));
        VenueScanJob job = venueScanJobRepository.save(new VenueScanJob(OWNER, venue.getId(),
                JobStatus.SCHEDULED, 0, Instant.now().minusSeconds(60)));
        LocalDateTime when = LocalDateTime.now().plusDays(12).truncatedTo(ChronoUnit.SECONDS);

        when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of(
                new Show("Flow Comedian", when, "Flow Comedy Club", "Austin", null, "x", "u", Show.Kind.COMEDY)));
        venueScanRunner.run(job);

        Show persisted = awaitUntil(
                () -> showRepository.findByOwnerOrderByEventDateTimeAsc(OWNER).stream()
                        .filter(s -> s.getArtistName().equals("Flow Comedian")).findFirst().orElse(null),
                s -> s != null);
        assertThat(persisted.getArtistId())
                .as("Part A: VenueScanRunner resolved this show to the existing catalog artist")
                .isEqualTo(performerId);

        controller().hideAndCancel(persisted.getId(), "eventDate", false, null, new ConcurrentModel());
        assertThat(artistRepository.findByIdAndOwner(performerId, OWNER).orElseThrow().getStatus())
                .isEqualTo(ArtistStatus.REJECTED);

        // The next real venue scan re-lists the exact same performer AND a fresh, never-seen-before
        // "Control Comedian" -- the positive control this test's own doc explains.
        when(scraper.scrapeShows(any(), any(), any(), any())).thenReturn(List.of(
                new Show("Flow Comedian", when, "Flow Comedy Club", "Austin", null, "x", "u", Show.Kind.COMEDY),
                new Show("Control Comedian", when, "Flow Comedy Club", "Austin", null, "x", "u", Show.Kind.COMEDY)));
        venueScanRunner.run(job);

        awaitUntil(() -> artistRepository.findByOwnerAndName(OWNER, "Control Comedian").orElse(null),
                a -> a != null);
        Long incomplete = awaitQuiescence(jdbcTemplate);
        assertThat(incomplete).as("every listener triggered by this rescan finished cleanly").isZero();

        List<Artist> all = artistRepository.findByOwner(OWNER);
        assertThat(all).extracting(Artist::getName)
                .as("still just the original rejected performer plus the one new control -- no "
                        + "duplicate Flow Comedian row from the rescan")
                .containsExactlyInAnyOrder("Flow Comedian", "Control Comedian");
        assertThat(artistRepository.findByIdAndOwner(performerId, OWNER).orElseThrow().getStatus())
                .as("still REJECTED -- not resurrected to PENDING_REVIEW by the next scan")
                .isEqualTo(ArtistStatus.REJECTED);
    }
}
