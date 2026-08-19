package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJobBackfill;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #163. A shared-scan owner must receive a REDUCED job set: scan jobs for the cheap sources only,
 * and no expansion jobs at all. Both failures are invisible from every page -- an over-expanding
 * shared scan would only ever surface as an LLM bill -- so they are pinned here.
 */
@SpringBootTest
@Testcontainers
// #203: the suite default (src/test/resources/application.properties) turns the backfill beans
// off so their own async startup fire can't pollute the owner-less admin-queue aggregates other
// tests assert on. This class autowires ScanJobBackfill/ExpandJobBackfill directly and drives
// them, so it needs the beans back -- same pattern as PollerFlowTest re-enabling the pollers.
@TestPropertySource(properties = "setlistscout.job-backfill-enabled=true")
class SharedScanGuardsTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String REAL_OWNER = "rob@example.com";

    @Autowired private ArtistRepository artistRepository;
    @Autowired private ScanJobRepository scanJobRepository;
    @Autowired private ExpandJobRepository expandJobRepository;
    @Autowired private ApplicationEventPublisher publisher;
    @Autowired private TransactionTemplate transactionTemplate;
    @Autowired private ScanJobBackfill scanJobBackfill;
    @Autowired private ExpandJobBackfill expandJobBackfill;
    @Autowired private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clean() {
        // Every test here publishes ArtistActivated/ArtistDeactivated directly and only awaits
        // ONE of ScanJobListener's/ExpandJobListener's two effects -- the other can still be
        // mid-transaction when the next test's deleteAll() runs. See awaitQuiescence's Javadoc.
        awaitQuiescence(jdbcTemplate);
        scanJobRepository.deleteAll();
        expandJobRepository.deleteAll();
        artistRepository.deleteAll();
    }

    private Artist seedArtist(String owner, String name) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        return artistRepository.save(artist);
    }

    /** ADR-0024: @ApplicationModuleListener is AFTER_COMMIT, so the publish must be inside a committing transaction. */
    private void publishActivated(String owner, Artist artist) {
        transactionTemplate.executeWithoutResult(tx ->
                publisher.publishEvent(new ArtistActivated(owner, artist.getId(), artist.getName(),
                        ArtistStatus.SEED.name())));
    }

    @Test
    @DisplayName("a shared-scan owner gets NO expansion jobs")
    void sharedOwnerGetsNoExpandJobs() {
        String sharedKey = SharedScanOwner.newKey();
        Artist artist = seedArtist(sharedKey, "Tom Petty");

        publishActivated(sharedKey, artist);

        List<?> jobs = awaitAbsence(() -> expandJobRepository.findAll(), j -> !j.isEmpty());
        assertThat(jobs)
                .as("expansion for a shared scan would fill a Candidates queue nobody can see "
                        + "and bill LLM calls per artist")
                .isEmpty();
    }

    @Test
    @DisplayName("a real owner still gets expansion jobs -- the guard must not disable expansion generally")
    void realOwnerStillGetsExpandJobs() {
        Artist artist = seedArtist(REAL_OWNER, "Tom Petty");

        publishActivated(REAL_OWNER, artist);

        assertThat(awaitUntil(() -> expandJobRepository.findAll(), j -> !j.isEmpty())).isNotEmpty();
    }

    @Test
    @DisplayName("a shared-scan owner gets scan jobs for ticketmaster and bandsintown only")
    void sharedOwnerGetsCheapSourcesOnly() {
        String sharedKey = SharedScanOwner.newKey();
        Artist artist = seedArtist(sharedKey, "Tom Petty");

        publishActivated(sharedKey, artist);

        List<ScanJob> jobs = awaitUntil(() -> scanJobRepository.findAll(), j -> !j.isEmpty());
        assertThat(jobs).isNotEmpty();
        assertThat(jobs).extracting(ScanJob::getSource)
                .as("band-site falls back to TourPageLlmService, which bills per artist")
                .containsOnly("ticketmaster", "bandsintown");
    }

    @Test
    @DisplayName("a real owner still gets a scan job for every source, band-site included")
    void realOwnerGetsEverySource() {
        Artist artist = seedArtist(REAL_OWNER, "Tom Petty");

        publishActivated(REAL_OWNER, artist);

        List<ScanJob> jobs = awaitUntil(() -> scanJobRepository.findAll(), j -> !j.isEmpty());
        assertThat(jobs).extracting(ScanJob::getSource).contains("band-site");
    }

    // ---- The startup backfills are a SECOND enqueue path that never touches the listeners.
    // findByStatusIn has no owner filter, so both backfills see shared-scan artists. Without the
    // same guards there, every application restart would undo them. These two tests are the only
    // thing that catches that.

    @Test
    @DisplayName("the expand backfill skips shared-scan artists")
    void expandBackfillSkipsSharedOwners() {
        String sharedKey = SharedScanOwner.newKey();
        seedArtist(sharedKey, "Tom Petty");
        seedArtist(REAL_OWNER, "Bruce Springsteen");

        expandJobBackfill.run(new DefaultApplicationArguments());

        assertThat(expandJobRepository.findAll())
                .as("a restart must not re-enqueue the expansion the listener guard prevents")
                .allSatisfy(job -> assertThat(job.getOwner()).isEqualTo(REAL_OWNER));
        assertThat(expandJobRepository.findAll()).isNotEmpty();
    }

    @Test
    @DisplayName("the scan backfill enqueues only cheap sources for shared-scan artists")
    void scanBackfillAppliesSourcePolicy() {
        String sharedKey = SharedScanOwner.newKey();
        seedArtist(sharedKey, "Tom Petty");
        seedArtist(REAL_OWNER, "Bruce Springsteen");

        scanJobBackfill.run(new DefaultApplicationArguments());

        assertThat(scanJobRepository.findAll())
                .filteredOn(job -> job.getOwner().equals(sharedKey))
                .isNotEmpty()
                .extracting(ScanJob::getSource)
                .containsOnly("ticketmaster", "bandsintown");
        assertThat(scanJobRepository.findAll())
                .filteredOn(job -> job.getOwner().equals(REAL_OWNER))
                .extracting(ScanJob::getSource)
                .contains("band-site");
    }
}
