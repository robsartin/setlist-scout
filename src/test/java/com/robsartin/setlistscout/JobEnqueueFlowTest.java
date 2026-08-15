package com.robsartin.setlistscout;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSeedService;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJob;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.scan.ScanJob;
import com.robsartin.setlistscout.scan.ScanJobRepository;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.GeocodingService;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Drives the REAL production publisher methods -- {@link ArtistActivationService#changeStatus}
 * and {@link SettingsService#updateSettings} -- with no test-only transaction wrapper (unlike a
 * Modulith {@code Scenario}, which wraps the publish in its own transaction -- see the #95
 * design review's T2 finding for why that class was retired), through to the
 * durable effect the {@code scan.ScanJobListener} and {@code expansion.ExpandJobListener}
 * {@code @ApplicationModuleListener}s produce in {@code scan_job}/{@code expand_job}.
 * <p>
 * This is the Task 6 regression guard called out in the PR3b plan: the PR3a final-review bug
 * was an event published outside a committed transaction, so the {@code AFTER_COMMIT}
 * {@code @ApplicationModuleListener} never fired in production even though a Scenario-based
 * test stayed green (Scenario wraps the publish in its own transaction). These tests would
 * fail the same way if {@code changeStatus}/{@code updateSettings} lost their
 * {@code @Transactional}, or if a listener weren't wired as a Spring bean.
 */
@SpringBootTest
@Testcontainers
class JobEnqueueFlowTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private ArtistActivationService artistActivationService;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ScanJobRepository scanJobRepository;

    @Autowired
    private ExpandJobRepository expandJobRepository;

    @Autowired
    private List<ShowSource> showSources;

    @Autowired
    private List<RelationSource> relationSources;

    @Autowired
    private ArtistSeedService artistSeedService;

    /**
     * The real ShowSource/RelationSource beans are left in the context (their id()s are what the
     * listeners fan out over -- exactly what's under test); only the external geocoder is
     * stubbed out, so settings persistence doesn't depend on network access to Zippopotam.us.
     */
    @MockitoBean
    private GeocodingService geocodingService;

    /**
     * The tribute-llm RelationSource is only ever enqueued for SEED artists (tribute/cover-band
     * expansion doesn't make sense for an artist a user explicitly approved from search results),
     * so an APPROVED artist gets one expand_job per RelationSource EXCEPT the tribute source. The
     * SEED path getting all {@code relationSources.size()} expand jobs, including tribute, is
     * covered by ExpandJobListenerTest's unit tests (and by
     * {@link #seedingAnArtistEnqueuesAllExpandJobsIncludingTribute()} below end-to-end).
     */
    private List<String> nonTributeExpandSourceIds() {
        return relationSources.stream()
                .filter(s -> s.classification() != ArtistSource.TRIBUTE_EXPANSION)
                .map(RelationSource::id)
                .toList();
    }

    @Test
    @DisplayName("changeStatus(APPROVED) publishes ArtistActivated in a committed tx, and the real "
            + "listeners enqueue one SCHEDULED scan_job per ShowSource and one per non-tribute RelationSource")
    void activatingAnArtistEnqueuesAllScanAndExpandJobs() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        String owner = "enqueue-flow@example.com";
        Long artistId = persistArtist(owner, "Enqueue Flow Artist", ArtistStatus.PENDING_REVIEW);
        List<String> expectedExpandSourceIds = nonTributeExpandSourceIds();

        artistActivationService.changeStatus(artistId, owner, ArtistStatus.APPROVED);

        List<ScanJob> scanJobs = awaitUntil(
                () -> scanJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == showSources.size());
        List<ExpandJob> expandJobs = awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == expectedExpandSourceIds.size());

        assertThat(scanJobs).as("one scan_job per ShowSource").hasSize(showSources.size());
        assertThat(scanJobs).allMatch(j -> j.getStatus() == JobStatus.SCHEDULED);
        assertThat(scanJobs).extracting(ScanJob::getSource)
                .containsExactlyInAnyOrderElementsOf(showSources.stream().map(ShowSource::id).toList());

        // APPROVED artists get no tribute expand_job: tribute expansion is SEED-only.
        assertThat(expandJobs).as("one expand_job per non-tribute RelationSource").hasSize(expectedExpandSourceIds.size());
        assertThat(expandJobs).allMatch(j -> j.getStatus() == JobStatus.SCHEDULED);
        assertThat(expandJobs).extracting(ExpandJob::getSource)
                .containsExactlyInAnyOrderElementsOf(expectedExpandSourceIds);
    }

    @Test
    @DisplayName("ArtistSeedService.addSeedIfNew publishes ArtistActivated(SEED) in a committed tx, "
            + "and the real listener enqueues one expand_job per RelationSource, including tribute-llm")
    void seedingAnArtistEnqueuesAllExpandJobsIncludingTribute() {
        String owner = "seed-flow@example.com";
        artistSeedService.addSeedIfNew(owner, "Seed Flow Artist");
        Long artistId = artistRepository.findByOwnerAndStatus(owner, ArtistStatus.SEED).get(0).getId();

        List<ExpandJob> expandJobs = awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == relationSources.size());

        assertThat(expandJobs).as("one expand_job per RelationSource, including tribute-llm for SEED")
                .hasSize(relationSources.size());
        assertThat(expandJobs).allMatch(j -> j.getStatus() == JobStatus.SCHEDULED);
        assertThat(expandJobs).extracting(ExpandJob::getSource)
                .containsExactlyInAnyOrderElementsOf(relationSources.stream().map(RelationSource::id).toList());
    }

    @Test
    @DisplayName("issue #124: manually re-adding a name matching a REJECTED artist reactivates it "
            + "to SEED through the real ArtistActivationService (not a direct repo save), and the "
            + "real listener enqueues all expand jobs including tribute-llm -- proving the "
            + "activation event actually fires")
    void reAddingARejectedArtistsNameReactivatesItAndEnqueuesJobs() {
        String owner = "reactivate-flow@example.com";
        Long artistId = persistArtist(owner, "Foo-Bar", ArtistStatus.REJECTED);

        // En-dash variant of the rejected name's hyphen -- proves the match goes through the
        // normalized-name ArtistNameMatcher (issue #124), not an exact-string lookup.
        boolean added = artistSeedService.addSeedIfNew(owner, "Foo–Bar");

        assertThat(added).as("the manual re-add is reported as effective").isTrue();
        Artist reactivated = artistRepository.findByIdAndOwner(artistId, owner).orElseThrow();
        assertThat(reactivated.getStatus())
                .as("the existing row was reactivated to SEED, not duplicated or left REJECTED")
                .isEqualTo(ArtistStatus.SEED);
        assertThat(artistRepository.findByOwnerAndStatusIn(owner,
                List.of(ArtistStatus.SEED, ArtistStatus.PENDING_REVIEW, ArtistStatus.APPROVED, ArtistStatus.REJECTED)))
                .as("still just the one artist row -- no duplicate created under the new spelling")
                .hasSize(1);

        List<ExpandJob> expandJobs = awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == relationSources.size());

        assertThat(expandJobs).as("reactivation enqueues one expand_job per RelationSource, including "
                        + "tribute-llm for SEED -- proves changeStatus's ArtistActivated event actually "
                        + "fired, which a direct repository save would not have published")
                .hasSize(relationSources.size());
        assertThat(expandJobs).allMatch(j -> j.getStatus() == JobStatus.SCHEDULED);
        assertThat(expandJobs).extracting(ExpandJob::getSource)
                .containsExactlyInAnyOrderElementsOf(relationSources.stream().map(RelationSource::id).toList());
    }

    @Test
    @DisplayName("updateSettings publishes SettingsChanged in a committed tx, and the real listener "
            + "re-dues every scan_job for the owner")
    void changingSettingsReDuesTheOwnersScanJobs() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        String owner = "settings-flow@example.com";
        Long artistId = persistArtist(owner, "Settings Flow Artist", ArtistStatus.PENDING_REVIEW);
        artistActivationService.changeStatus(artistId, owner, ArtistStatus.APPROVED);
        List<ScanJob> initialJobs = awaitUntil(
                () -> scanJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == showSources.size());
        long initialVersion = initialJobs.get(0).getVersion();

        Instant beforeUpdate = Instant.now();
        settingsService.updateSettings(owner, "10001", 75, 3);

        List<ScanJob> reDuedJobs = awaitUntil(
                () -> scanJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == showSources.size()
                        && jobs.stream().allMatch(j -> !j.getNextDueAt().isBefore(beforeUpdate)));

        assertThat(reDuedJobs).as("every job's nextDueAt advanced to (at or after) the settings change")
                .allMatch(j -> !j.getNextDueAt().isBefore(beforeUpdate));
        assertThat(reDuedJobs).as("redueAll's version bump proves this is a real re-due, not a "
                + "coincidentally-already-scheduled job").allMatch(j -> j.getVersion() > initialVersion);
        assertThat(reDuedJobs).as("every job is SCHEDULED and cleanly claimable again (redueAll)")
                .allMatch(j -> j.getStatus() == JobStatus.SCHEDULED
                        && j.getAttempts() == 0
                        && j.getClaimedAt() == null);
    }

    @Test
    @DisplayName("activation is idempotent against a pre-existing scan_job row: the real ON CONFLICT "
            + "insert absorbs the conflicting source and still enqueues the rest, in one pass")
    void activationIsIdempotentAgainstAPreExistingScanJob() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        String owner = "idempotent-flow@example.com";
        Long artistId = persistArtist(owner, "Idempotent Flow Artist", ArtistStatus.PENDING_REVIEW);

        // Simulate a job that's already been enqueued for one source (e.g. a redelivered event, or
        // a concurrent activation that beat this one to the punch) -- exactly the row that would
        // make an existsBy+save+catch enqueue collide on the (owner, artist_id, source) unique
        // constraint. On Postgres, a real DataIntegrityViolationException from a mid-loop save
        // aborts the whole @ApplicationModuleListener transaction, so the earlier fix's catch block
        // still lost every job after the one that conflicted -- this reproduces that exact shape
        // against the real DB, which mocks can't.
        ScanJob preExisting = new ScanJob(artistId, "ticketmaster", JobStatus.SCHEDULED, 0, Instant.now());
        preExisting.setOwner(owner);
        scanJobRepository.save(preExisting);
        List<String> expectedExpandSourceIds = nonTributeExpandSourceIds();

        artistActivationService.changeStatus(artistId, owner, ArtistStatus.APPROVED);

        List<ScanJob> scanJobs = awaitUntil(
                () -> scanJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == showSources.size());
        List<ExpandJob> expandJobs = awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == expectedExpandSourceIds.size());

        assertThat(scanJobs).as("the pre-existing source wasn't duplicated, and the rest were enqueued")
                .hasSize(showSources.size());
        assertThat(scanJobs).extracting(ScanJob::getSource)
                .containsExactlyInAnyOrderElementsOf(showSources.stream().map(ShowSource::id).toList());
        // APPROVED artists get no tribute expand_job: tribute expansion is SEED-only.
        assertThat(expandJobs).as("expand_job enqueue completed in the same pass, unaffected by the "
                + "scan_job conflict").hasSize(expectedExpandSourceIds.size());
        assertThat(expandJobs).extracting(ExpandJob::getSource)
                .containsExactlyInAnyOrderElementsOf(expectedExpandSourceIds);
    }

    @Test
    @DisplayName("changeStatus(REJECTED) publishes ArtistDeactivated in a committed tx, and the real "
            + "listeners delete the artist's scan_job and expand_job rows")
    void deactivatingAnArtistCancelsItsScanAndExpandJobs() {
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        String owner = "cancel-flow@example.com";
        Long artistId = persistArtist(owner, "Cancel Flow Artist", ArtistStatus.PENDING_REVIEW);
        artistActivationService.changeStatus(artistId, owner, ArtistStatus.APPROVED);
        awaitUntil(
                () -> scanJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == showSources.size());
        awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(owner, artistId),
                jobs -> jobs.size() == nonTributeExpandSourceIds().size());

        artistActivationService.changeStatus(artistId, owner, ArtistStatus.REJECTED);

        List<ScanJob> remainingScanJobs = awaitUntil(
                () -> scanJobRepository.findByOwnerAndArtistId(owner, artistId),
                List::isEmpty);
        List<ExpandJob> remainingExpandJobs = awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(owner, artistId),
                List::isEmpty);

        assertThat(remainingScanJobs).as("scan_job rows cancelled on deactivation").isEmpty();
        assertThat(remainingExpandJobs).as("expand_job rows cancelled on deactivation").isEmpty();
    }

    private Long persistArtist(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist).getId();
    }
}
