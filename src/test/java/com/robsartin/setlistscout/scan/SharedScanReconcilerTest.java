package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.SharedScanOwner;
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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Testcontainers
class SharedScanReconcilerTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ROB = "rob@example.com";
    private static final String DAVID = "david@example.com";
    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    @Autowired private SharedScanReconciler reconciler;
    @Autowired private SharedScanRepository sharedScanRepository;
    @Autowired private ArtistRepository artistRepository;
    @Autowired private ScanJobRepository scanJobRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private SharedScan scan;

    @BeforeEach
    void setUp() {
        // Drains any @ApplicationModuleListener work still in flight from the previous test
        // before deleting the rows it might be writing -- see awaitQuiescence's Javadoc.
        awaitQuiescence(jdbcTemplate);
        scanJobRepository.deleteAll();
        artistRepository.deleteAll();
        sharedScanRepository.deleteAll();
        scan = sharedScanRepository.save(
                new SharedScan(SharedScanOwner.newKey(), ROB, DAVID, "Rob & David"));
    }

    private void seed(String owner, String name, ArtistStatus status) {
        Artist artist = new Artist(name, ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(owner);
        artistRepository.save(artist);
    }

    private List<String> activeSharedNames() {
        return artistRepository.findByOwnerAndStatusIn(scan.getOwnerKey(), ACTIVE)
                .stream().map(Artist::getName).sorted().toList();
    }

    @Test
    @DisplayName("materializes the normalized intersection as artists under the shared key")
    void materializesIntersection() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);
        seed(ROB, "Only Rob", ArtistStatus.SEED);

        reconciler.reconcile(scan);

        assertThat(activeSharedNames()).containsExactly("Tom Petty");
    }

    @Test
    @DisplayName("is idempotent -- reconciling twice does not duplicate artists")
    void isIdempotent() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);

        reconciler.reconcile(scan);
        reconciler.reconcile(scan);

        assertThat(activeSharedNames()).containsExactly("Tom Petty");
    }

    @Test
    @DisplayName("removes an artist that has left the intersection")
    void removesDepartedArtist() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);
        reconciler.reconcile(scan);
        assertThat(activeSharedNames()).containsExactly("Tom Petty");

        // David rejects the artist -- it is no longer shared.
        Artist davids = artistRepository.findByOwnerAndName(DAVID, "Tom petty").orElseThrow();
        davids.setStatus(ArtistStatus.REJECTED);
        artistRepository.save(davids);

        reconciler.reconcile(scan);

        assertThat(activeSharedNames()).isEmpty();
    }

    @Test
    @DisplayName("creating shared artists enqueues their scan jobs via the normal event path")
    void enqueuesScanJobs() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);

        reconciler.reconcile(scan);

        List<ScanJob> jobs = awaitUntil(() -> scanJobRepository.findAll(), j -> !j.isEmpty());
        assertThat(jobs).isNotEmpty();
        assertThat(jobs).allSatisfy(j -> assertThat(j.getOwner()).isEqualTo(scan.getOwnerKey()));
        // Task 2's guard applies here too -- shared scans never get band-site.
        assertThat(jobs).extracting(ScanJob::getSource).containsOnly("ticketmaster", "bandsintown");
    }

    @Test
    @DisplayName("reconcile settles -- it does not keep re-triggering itself")
    void reconcileSettlesToTheIntersectionWithoutRepeating() {
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        seed(DAVID, "Tom petty", ArtistStatus.SEED);

        reconciler.reconcile(scan);

        // Creating the shared artist publishes ArtistActivated(sharedKey), which this same class
        // also listens for. Verified empirically (Task 4 fix round, #163): recursion cannot
        // actually form today, guard or no guard -- findByOwnerAIgnoreCaseOrOwnerBIgnoreCase
        // matches only the ownerA/ownerB participant columns, and a shared key is never stored
        // there, so onParticipantArtistChanged's lookup comes back empty regardless. This test
        // therefore observes that reconcile settles to a stable single artist, not that the guard
        // is what prevents a loop -- see onParticipantArtistChanged's Javadoc for what the guard
        // actually protects against.
        List<String> settled = awaitUntil(this::activeSharedNames, names -> names.size() == 1);
        assertThat(settled).containsExactly("Tom Petty");
    }

    @Test
    @DisplayName("a participant activating an artist reconciles the shared scan automatically")
    void participantChangeTriggersReconcile() {
        seed(DAVID, "Tom petty", ArtistStatus.SEED);
        reconciler.reconcile(scan);
        assertThat(activeSharedNames()).isEmpty();

        // Rob now adds the same artist -- it becomes shared, and the listener should notice.
        seed(ROB, "Tom Petty", ArtistStatus.SEED);
        Artist robs = artistRepository.findByOwnerAndName(ROB, "Tom Petty").orElseThrow();
        reconciler.onParticipantArtistChanged(ROB, robs.getId());

        assertThat(awaitUntil(this::activeSharedNames, n -> !n.isEmpty())).containsExactly("Tom Petty");
    }
}
