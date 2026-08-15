package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.expansion.ExpandJob;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.scan.ScanJob;
import com.robsartin.setlistscout.scan.ScanJobRepository;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.shared.CurrentUser;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.TestPropertySource;
import org.springframework.ui.ConcurrentModel;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Real-path Testcontainers proof of issue #117's {@code POST /artists/{id}/remove-from-seed}:
 * drives {@link ArtistController#removeFromSeed} through the REAL {@link ArtistActivationService}
 * and the real {@code scan.ScanJobListener}/{@code expansion.ExpandJobListener}
 * {@code @ApplicationModuleListener}s (not a mock), so a green result actually proves {@code
 * ArtistDeactivated} fired in a committed transaction and the listeners' cleanup ran -- a
 * Modulith {@code Scenario} test would be a false green per ADR-0024. Mirrors {@link
 * com.robsartin.setlistscout.JobEnqueueFlowTest}'s and {@link ArtistSeedServiceFlowTest}'s style:
 * autowire the real collaborators, but construct {@link ArtistController} directly with a stubbed
 * {@link CurrentUser} (a plain Mockito mock, not a Spring bean) so each test can control the
 * acting owner without standing up MockMvc/security.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false"
})
class RemoveFromSeedFlowTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "remove-seed-flow@example.com";

    @Autowired
    private ArtistRepository artistRepository;

    @Autowired
    private ArtistEdgeRepository artistEdgeRepository;

    @Autowired
    private ArtistSeedService artistSeedService;

    @Autowired
    private ArtistActivationService artistActivationService;

    @Autowired
    private ArtistConnectionsService artistConnectionsService;

    @Autowired
    private ScanJobRepository scanJobRepository;

    @Autowired
    private ExpandJobRepository expandJobRepository;

    @Autowired
    private List<ShowSource> showSources;

    @Autowired
    private List<RelationSource> relationSources;

    private CurrentUser currentUser;

    @BeforeEach
    void setUp() {
        // Shared container/context across this class's test methods (see
        // AbstractPostgresIntegrationTest and ArtistSeedServiceFlowTest's identical note) -- clear
        // first so a prior method's committed rows don't collide on (owner, name).
        artistRepository.deleteAll();
        currentUser = mock(CurrentUser.class);
    }

    private ArtistController controller() {
        return new ArtistController(artistRepository, artistEdgeRepository, currentUser, artistSeedService,
                artistActivationService, artistConnectionsService);
    }

    private Long seedArtist(String owner, String name) {
        assertThat(artistSeedService.addSeedIfNew(owner, name)).isTrue();
        return artistRepository.findByOwnerAndStatus(owner, ArtistStatus.SEED).stream()
                .filter(a -> a.getName().equals(name))
                .findFirst().orElseThrow().getId();
    }

    @Test
    @DisplayName("issue #117: removeFromSeed transitions a SEED artist to REMOVED through the real "
            + "ArtistActivationService, and the real listeners cancel its scan_job/expand_job rows -- "
            + "proving ArtistDeactivated actually fired, not just that the status column changed")
    void removeFromSeedTransitionsToRemovedAndCancelsJobs() {
        when(currentUser.email()).thenReturn(OWNER);
        Long artistId = seedArtist(OWNER, "Remove Flow Artist");

        // Confirm the SEED artist really has live scan_job/expand_job rows before removing it --
        // otherwise "rows are gone" afterward would be true for the trivial reason they never
        // existed.
        awaitUntil(() -> scanJobRepository.findByOwnerAndArtistId(OWNER, artistId),
                jobs -> jobs.size() == showSources.size());
        awaitUntil(() -> expandJobRepository.findByOwnerAndArtistId(OWNER, artistId),
                jobs -> jobs.size() == relationSources.size());

        String view = controller().removeFromSeed(artistId, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists");
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
    @DisplayName("issue #117: removeFromSeed with a foreign owner's artist id is a silent no-op -- "
            + "the artist's status and jobs are untouched, not a data leak")
    void removeFromSeedForeignOwnerIsNoOp() {
        when(currentUser.email()).thenReturn(OWNER);
        Long artistId = seedArtist(OWNER, "Owned By Someone Else");
        awaitUntil(() -> expandJobRepository.findByOwnerAndArtistId(OWNER, artistId),
                jobs -> jobs.size() == relationSources.size());

        when(currentUser.email()).thenReturn("intruder@example.com");
        String view = controller().removeFromSeed(artistId, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists");
        Artist unchanged = artistRepository.findByIdAndOwner(artistId, OWNER).orElseThrow();
        assertThat(unchanged.getStatus()).as("foreign owner's call left the real owner's artist alone")
                .isEqualTo(ArtistStatus.SEED);
        assertThat(expandJobRepository.findByOwnerAndArtistId(OWNER, artistId))
                .as("no job cancellation ran -- the no-op never published ArtistDeactivated")
                .hasSize(relationSources.size());
    }
}
