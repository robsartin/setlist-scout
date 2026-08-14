package com.robsartin.setlistscout;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJob;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.expansion.ExpandPoller;
import com.robsartin.setlistscout.expansion.source.LastFmSimilarSource;
import com.robsartin.setlistscout.scan.ScanJob;
import com.robsartin.setlistscout.scan.ScanJobRepository;
import com.robsartin.setlistscout.scan.ScanPoller;
import com.robsartin.setlistscout.scan.Show;
import com.robsartin.setlistscout.scan.ShowRepository;
import com.robsartin.setlistscout.scan.source.TicketmasterShowSource;
import com.robsartin.setlistscout.settings.GeocodingService;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.JobStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end regression guard for the whole PR4a paced-poller engine: it flips the two
 * {@code @ConditionalOnProperty} pollers ON (they're default-off, so they don't even exist as
 * beans in the other integration tests) and drives the REAL claim-run-reschedule path against a
 * live Postgres -- enqueue a due job, invoke the poller's own {@code tick()}, and assert both the
 * durable domain effect (a persisted {@code show_event} row / a PENDING_REVIEW {@code artist}
 * from the real {@code CandidateDiscovered} -> {@code CandidatePersistenceListener} path) AND the
 * job's own bookkeeping (rescheduled on success, backed off on failure).
 * <p>
 * Only the leaf source ports are mocked ({@link ShowSource}/{@link RelationSource} -- external
 * HTTP/LLM adapters) plus {@link GeocodingService}; everything between the poller and the DB is
 * the production wiring. {@code tick()} is invoked directly rather than waiting on the
 * {@code @Scheduled} fire so the timing is deterministic; the enable flags are still on so the
 * poller beans genuinely exist.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=true",
        "setlistscout.expand-poller-enabled=true",
        // The Task 4 startup backfill (scan.ScanJobBackfill / expansion.ExpandJobBackfill) is a
        // synchronous ApplicationRunner: it runs once during context refresh, before any @Test
        // method's own `when(...)` stubbing has happened. CatalogSeeder always seeds real SEED
        // artists at startup regardless, so backfill would try to enqueue jobs for them using
        // ticketmasterShowSource/lastFmSource below while their id() is still an unstubbed-null
        // Mockito default -- a real NOT NULL violation, unlike the async ScanJobListener/
        // ExpandJobListener path this class doesn't otherwise exercise for those seeded artists.
        "setlistscout.job-backfill-enabled=false"
})
class PollerFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-client-secret");
    }

    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    @Autowired
    private ScanPoller scanPoller;

    @Autowired
    private ExpandPoller expandPoller;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ScanJobRepository scanJobRepository;

    @Autowired
    private ExpandJobRepository expandJobRepository;

    @Autowired
    private ShowRepository showRepository;

    @Autowired
    private SettingsService settingsService;

    /**
     * Replace exactly ONE concrete bean per source port. Mocking the concrete class (not the
     * {@code ShowSource}/{@code RelationSource} interface) matters if any other bean ever comes to
     * autowire one of these sources by its concrete type -- an interface-typed mock would leave
     * that concrete-type dependency unsatisfied. The mock still
     * lands in the injected {@code List<ShowSource>}/{@code List<RelationSource>}; the runners filter
     * to the single source whose {@code id()} equals the job's {@code source}, so the remaining real
     * sources are never invoked. Stubbing {@code id()} in each test is required: an unstubbed Mockito
     * mock returns {@code null}, and the runner calls {@code s.id().equals(..)} on every source.
     */
    @MockBean
    private TicketmasterShowSource ticketmasterShowSource;

    @MockBean
    private LastFmSimilarSource lastFmSource;

    /** Stubbed empty so settings persistence never hits the network geocoder. */
    @MockBean
    private GeocodingService geocodingService;

    @Test
    @DisplayName("scan poller: claims a due scan_job, persists the found Show via the real "
            + "ScanUnitRunner, and reschedules the job into the future")
    void scanHappyPath() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        String owner = "scan-happy@example.com";
        Long artistId = persistArtist(owner, "Scan Happy Artist");
        settingsService.getOrCreateSettings(owner);

        LocalDateTime when = LocalDateTime.now().plusDays(30).withNano(0);
        Show found = new Show("Scan Happy Artist", when, "Moody Center", "Austin",
                new BigDecimal("42.00"), "ticketmaster", "https://tix.example/1");
        when(ticketmasterShowSource.id()).thenReturn("ticketmaster");
        when(ticketmasterShowSource.search(any())).thenReturn(List.of(found));

        ScanJob job = enqueueScanJob(owner, artistId, "ticketmaster");

        scanPoller.tick();

        // The Show row is persisted by the real ScanUnitRunner.persistNew.
        awaitUntil(
                () -> showRepository.existsByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                        owner, "Scan Happy Artist", when, "Moody Center"),
                Boolean::booleanValue);
        assertThat(showRepository.existsByOwnerAndArtistNameAndEventDateTimeAndVenueName(
                owner, "Scan Happy Artist", when, "Moody Center"))
                .as("the mocked show was persisted through the real scan path").isTrue();

        ScanJob rescheduled = scanJobRepository.findById(job.getId()).orElseThrow();
        assertThat(rescheduled.getStatus()).as("back to SCHEDULED after a successful run")
                .isEqualTo(JobStatus.SCHEDULED);
        assertThat(rescheduled.getClaimedAt()).as("claim released").isNull();
        assertThat(rescheduled.getAttempts()).as("attempts reset on success").isZero();
        assertThat(rescheduled.getNextDueAt()).as("next_due_at advanced into the future")
                .isAfter(Instant.now());
    }

    @Test
    @DisplayName("expand poller: claims a due expand_job, and the real CandidateDiscovered -> "
            + "CandidatePersistenceListener path creates a PENDING_REVIEW artist; job reschedules")
    void expandHappyPath() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        String owner = "expand-happy@example.com";
        Long baseArtistId = persistArtist(owner, "Expand Base Artist");

        when(lastFmSource.id()).thenReturn("lastfm");
        when(lastFmSource.classification()).thenReturn(ArtistSource.SIMILAR_EXPANSION);
        when(lastFmSource.note(any())).thenReturn("similar to Expand Base Artist");
        when(lastFmSource.related("Expand Base Artist")).thenReturn(List.of("Discovered Candidate Band"));

        ExpandJob job = enqueueExpandJob(owner, baseArtistId, "lastfm");

        expandPoller.tick();

        // The async @ApplicationModuleListener (AFTER_COMMIT) persists the candidate slightly
        // after the poller run commits -- await it.
        awaitUntil(
                () -> artistRepository.existsByOwnerAndNameIgnoreCase(owner, "Discovered Candidate Band"),
                Boolean::booleanValue);
        assertThat(artistRepository.existsByOwnerAndNameIgnoreCase(owner, "Discovered Candidate Band"))
                .as("candidate persisted as a PENDING_REVIEW artist via the real listener").isTrue();
        Artist candidate = artistRepository.findByOwnerAndStatus(owner, ArtistStatus.PENDING_REVIEW).stream()
                .filter(a -> "Discovered Candidate Band".equals(a.getName()))
                .findFirst().orElseThrow();
        assertThat(candidate.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);

        ExpandJob rescheduled = expandJobRepository.findById(job.getId()).orElseThrow();
        assertThat(rescheduled.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(rescheduled.getClaimedAt()).isNull();
        assertThat(rescheduled.getAttempts()).isZero();
        assertThat(rescheduled.getNextDueAt()).as("next_due_at advanced into the future")
                .isAfter(Instant.now());
    }

    @Test
    @DisplayName("expand poller: the real CandidateDiscovered -> CandidatePersistenceListener path "
            + "completes cleanly (no exception, no duplicate) against a pre-existing (owner, name) "
            + "artist row, end to end through the real production publisher (#95 D1)")
    void expandCandidatePersistIsIdempotentAgainstAPreExistingArtist() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        String owner = "expand-idempotent@example.com";
        Long baseArtistId = persistArtist(owner, "Expand Idempotent Base Artist");

        // Simulate a candidate that's already been persisted for this owner (e.g. a redelivered
        // event, or a concurrent expansion that beat this one to the punch). End to end, the
        // listener's existsByOwnerAndNameIgnoreCase pre-check absorbs this exact case; the DB-level
        // ON CONFLICT guard this pre-check backs up is proven directly (bypassing the pre-check,
        // which a real check-then-insert race would) by catalog.ArtistRepositoryTest.
        Artist preExisting = new Artist("Discovered Candidate Band", ArtistSource.SIMILAR_EXPANSION,
                ArtistStatus.PENDING_REVIEW, "Expand Idempotent Base Artist",
                "similar to Expand Idempotent Base Artist");
        preExisting.setOwner(owner);
        artistRepository.save(preExisting);

        when(lastFmSource.id()).thenReturn("lastfm");
        when(lastFmSource.classification()).thenReturn(ArtistSource.SIMILAR_EXPANSION);
        when(lastFmSource.note(any())).thenReturn("similar to Expand Idempotent Base Artist");
        when(lastFmSource.related("Expand Idempotent Base Artist")).thenReturn(List.of("Discovered Candidate Band"));

        ExpandJob job = enqueueExpandJob(owner, baseArtistId, "lastfm");

        expandPoller.tick();

        // The job reschedules cleanly -- proving the listener's transaction wasn't poisoned by
        // the real (owner, name) unique-constraint conflict.
        ExpandJob rescheduled = awaitUntil(
                () -> expandJobRepository.findById(job.getId()).orElseThrow(),
                j -> j.getStatus() == JobStatus.SCHEDULED);
        assertThat(rescheduled.getStatus()).as("job completed and rescheduled without exception")
                .isEqualTo(JobStatus.SCHEDULED);
        assertThat(rescheduled.getAttempts()).isZero();

        List<Artist> candidates = artistRepository.findByOwnerAndStatus(owner, ArtistStatus.PENDING_REVIEW).stream()
                .filter(a -> "Discovered Candidate Band".equals(a.getName()))
                .toList();
        assertThat(candidates).as("no duplicate row: the ON CONFLICT insert absorbed the race").hasSize(1);
    }

    @Test
    @DisplayName("scan poller: a source that throws leaves the job FAILED with attempts=1, a "
            + "populated last_error, and next_due_at backed off into the future")
    void scanFailureBacksOff() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        String owner = "scan-fail@example.com";
        Long artistId = persistArtist(owner, "Scan Fail Artist");
        settingsService.getOrCreateSettings(owner);

        when(ticketmasterShowSource.id()).thenReturn("ticketmaster");
        when(ticketmasterShowSource.search(any()))
                .thenThrow(new RuntimeException("ticketmaster boom"));

        ScanJob job = enqueueScanJob(owner, artistId, "ticketmaster");

        scanPoller.tick();

        ScanJob failed = awaitUntil(
                () -> scanJobRepository.findById(job.getId()).orElseThrow(),
                j -> j.getStatus() == JobStatus.FAILED);
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getClaimedAt()).as("claim released even on failure").isNull();
        assertThat(failed.getLastError()).as("failure detail captured (<=8000 chars)")
                .isNotNull()
                .hasSizeLessThanOrEqualTo(8000)
                .contains("ticketmaster boom");
        assertThat(failed.getNextDueAt()).as("backed off into the future")
                .isAfter(Instant.now());
    }

    @Test
    @DisplayName("expand poller: a source that throws leaves the job FAILED with attempts=1, "
            + "a populated last_error, and a backed-off next_due_at")
    void expandFailureBacksOff() {
        String owner = "expand-fail@example.com";
        Long artistId = persistArtist(owner, "Expand Fail Artist");

        when(lastFmSource.id()).thenReturn("lastfm");
        when(lastFmSource.related(any())).thenThrow(new RuntimeException("lastfm boom"));

        ExpandJob job = enqueueExpandJob(owner, artistId, "lastfm");

        expandPoller.tick();

        ExpandJob failed = awaitUntil(
                () -> expandJobRepository.findById(job.getId()).orElseThrow(),
                j -> j.getStatus() == JobStatus.FAILED);
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getClaimedAt()).isNull();
        assertThat(failed.getLastError()).isNotNull().hasSizeLessThanOrEqualTo(8000).contains("lastfm boom");
        assertThat(failed.getNextDueAt()).isAfter(Instant.now());
    }

    /**
     * Persist an APPROVED artist with a pre-set official-site URL so the scan path's
     * {@code resolveSiteUrl} never falls through to the (real) MusicBrainz lookup.
     */
    private Long persistArtist(String owner, String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.APPROVED, null, null);
        artist.setOwner(owner);
        artist.setOfficialSiteUrl("https://official.example/" + name.replace(' ', '-'));
        return artistRepository.save(artist).getId();
    }

    /** A due (next_due_at in the past), unclaimed, SCHEDULED scan_job for the poller to claim. */
    private ScanJob enqueueScanJob(String owner, Long artistId, String source) {
        ScanJob job = new ScanJob(artistId, source, JobStatus.SCHEDULED, 0, Instant.now().minusSeconds(60));
        job.setOwner(owner);
        return scanJobRepository.save(job);
    }

    /** A due, unclaimed, SCHEDULED expand_job for the poller to claim. */
    private ExpandJob enqueueExpandJob(String owner, Long artistId, String source) {
        ExpandJob job = new ExpandJob(artistId, source, JobStatus.SCHEDULED, 0,
                Instant.now().minusSeconds(60));
        job.setOwner(owner);
        return expandJobRepository.save(job);
    }

    /**
     * Bounded manual poll -- no fixed sleep -- for an async effect to land. Mirrors
     * JobEnqueueFlowTest's helper (Awaitility isn't a project dependency); returns the last fetched
     * value regardless of whether {@code condition} was met, so a timeout still fails with a useful
     * diff instead of a bare assertion.
     */
    private <T> T awaitUntil(Supplier<T> fetch, Predicate<T> condition) {
        Instant deadline = Instant.now().plus(AWAIT_TIMEOUT);
        T last = fetch.get();
        while (!condition.test(last) && Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
            last = fetch.get();
        }
        return last;
    }
}
