package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.DiscogsRelationSource;
import com.robsartin.setlistscout.expansion.source.LastFmSimilarSource;
import com.robsartin.setlistscout.expansion.source.MusicBrainzRelationSource;
import com.robsartin.setlistscout.expansion.source.SimilarLlmSource;
import com.robsartin.setlistscout.expansion.source.TributeLlmSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Drives the REAL production path -- {@link ExpansionService#expandAll} publishing
 * {@link com.robsartin.setlistscout.shared.events.CandidateDiscovered} the way the scheduled
 * job/controller do, with no test-only transaction wrapper -- through to the persisted
 * PENDING_REVIEW {@link Artist}.
 * <p>
 * This is the regression guard for the PR3a final-review CRITICAL bug: {@code expandAll} used
 * to publish with no active transaction, so the {@code @ApplicationModuleListener} (which is
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}) never fired in production and no
 * candidates were ever persisted -- even though {@link
 * com.robsartin.setlistscout.catalog.CandidateDiscoveredFlowTest} stayed green, because it
 * publishes via Modulith's {@code Scenario}, which wraps the publish in its own transaction.
 * This test would FAIL before the {@code ExpansionService} fix (publish wrapped in a
 * {@code TransactionTemplate}) and PASSES after it.
 */
@SpringBootTest
@Testcontainers
class ExpansionEventFlowTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-client-secret");
    }

    private static final String OWNER = "expansion-flow@example.com";
    private static final String BASE_NAME = "Base Artist";
    private static final String CANDIDATE_NAME = "A Real Candidate";

    @Autowired
    private ExpansionService expansionService;

    @Autowired
    private ArtistRepository artistRepository;

    @MockBean
    private MusicBrainzRelationSource musicBrainzSource;

    @MockBean
    private DiscogsRelationSource discogsSource;

    @MockBean
    private LastFmSimilarSource lastFmSource;

    @MockBean
    private SimilarLlmSource similarLlmSource;

    @MockBean
    private TributeLlmSource tributeSource;

    @Test
    @DisplayName("expandAll publishes CandidateDiscovered in a committed tx, and the async listener "
            + "persists a PENDING_REVIEW artist -- the real (non-Scenario) path")
    void expandAllPersistsADiscoveredCandidateViaTheRealPath() {
        Artist base = new Artist(BASE_NAME, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        base.setOwner(OWNER);
        artistRepository.save(base);

        when(musicBrainzSource.related(BASE_NAME)).thenReturn(List.of(CANDIDATE_NAME));
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());
        when(tributeSource.related(any())).thenReturn(List.of());

        expansionService.expandAll(OWNER);

        Optional<Artist> persisted = awaitPendingReviewArtist(OWNER, CANDIDATE_NAME, Duration.ofSeconds(10));

        assertThat(persisted)
                .as("candidate '%s' should be persisted as PENDING_REVIEW for owner '%s' within the timeout",
                        CANDIDATE_NAME, OWNER)
                .isPresent();
        assertThat(persisted.get().getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
    }

    /**
     * Bounded manual poll -- no fixed sleep -- for the async {@code @ApplicationModuleListener}
     * to finish persisting the candidate. Awaitility isn't a project dependency, so this is a
     * plain poll loop instead of adding one for a single test.
     */
    private Optional<Artist> awaitPendingReviewArtist(String owner, String name, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            Optional<Artist> found = artistRepository.findByOwnerAndStatus(owner, ArtistStatus.PENDING_REVIEW)
                    .stream()
                    .filter(a -> a.getName().equalsIgnoreCase(name))
                    .findFirst();
            if (found.isPresent()) {
                return found;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }
        return Optional.empty();
    }
}
