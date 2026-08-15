package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.expansion.ExpandJob;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.scan.ScanJob;
import com.robsartin.setlistscout.scan.ScanJobRepository;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.event.EventListener;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

/**
 * Real-path Testcontainers proof of issue #133: {@link ArtistSeedService#addSeedIfNew}'s
 * "not found" branch used to be a check-then-insert race on the real {@code artist (owner, name)}
 * unique constraint -- two near-simultaneous calls for the same brand-new name could both pass the
 * {@link ArtistNameMatcher} pre-check, then one would throw {@code DataIntegrityViolationException}
 * on flush. This actually happened in production: {@code ERROR: duplicate key value violates
 * unique constraint "ukr03a96lqhsb7djq2tn4rq60vn" Detail: Key (name)=(Nebraska) already exists.}
 * <p>
 * A {@link MockitoSpyBean} on the real {@link ArtistNameMatcher} forces the exact interleave the
 * bug needs -- both threads' pre-check must return "no match" before either thread's insert runs --
 * the same technique {@code PollerFlowTest}'s {@code concurrentRedueDuringPollWinsOverStaleReschedule}
 * (#95 T1) uses to force a real two-connection race deterministically, rather than hoping two
 * threads happen to interleave badly. Everything downstream of the matcher -- the repository, the
 * transaction, the real {@link ArtistActivationService}, and the real {@code ArtistActivated}
 * publish -- is production wiring against a real Postgres, not a mock (a Modulith {@code Scenario}
 * test would be a false green here: see CLAUDE.md's invariant on that).
 */
@SpringBootTest
@Testcontainers
@Import(ArtistSeedServiceRaceTest.CapturingListenerConfig.class)
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false"
})
class ArtistSeedServiceRaceTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "seed-race-133@example.com";
    private static final String NAME = "Nebraska";

    @Autowired
    private ArtistSeedService artistSeedService;

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

    @MockitoSpyBean
    private ArtistNameMatcher artistNameMatcher;

    @Autowired
    private CapturingListener artistActivatedListener;

    @BeforeEach
    void setUp() {
        // Shared container/context across this class's test methods (see
        // AbstractPostgresIntegrationTest) -- clear first so a prior method's committed rows don't
        // collide on (owner, name).
        artistRepository.deleteAll();
        artistActivatedListener.received.clear();
    }

    @Test
    @DisplayName("issue #133: two concurrent addSeedIfNew calls for the same brand-new (owner, name) "
            + "race at the DB's (owner, name) unique constraint -- neither call lets an exception "
            + "propagate, exactly one Artist row is created, and ArtistActivated fires exactly once "
            + "(so jobs are enqueued exactly once, not zero or twice)")
    void concurrentAddSeedIfNewForTheSameNewNameRacesSafely() throws Exception {
        // Forces the real interleave the production bug needs: both threads' matcher pre-check
        // must return "no match" BEFORE either thread's own insert runs, so both fall into the
        // "not found" branch and race for real at the (owner, name) unique constraint -- not a
        // mocked repository, a real Postgres constraint, across two real concurrent transactions.
        CountDownLatch bothCheckedNoMatch = new CountDownLatch(2);
        CountDownLatch releaseInserts = new CountDownLatch(1);
        doAnswer(invocation -> {
            Optional<?> result = (Optional<?>) invocation.callRealMethod();
            bothCheckedNoMatch.countDown();
            releaseInserts.await(10, TimeUnit.SECONDS);
            return result;
        }).when(artistNameMatcher).findExistingMatch(eq(OWNER), eq(NAME));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> call1 = executor.submit(() -> artistSeedService.addSeedIfNew(OWNER, NAME));
            Future<Boolean> call2 = executor.submit(() -> artistSeedService.addSeedIfNew(OWNER, NAME));

            assertThat(bothCheckedNoMatch.await(10, TimeUnit.SECONDS))
                    .as("both calls read 'no existing match' and are blocked right before their own insert")
                    .isTrue();
            releaseInserts.countDown();

            assertThatCode(() -> {
                boolean r1 = call1.get(10, TimeUnit.SECONDS);
                boolean r2 = call2.get(10, TimeUnit.SECONDS);
                // Exactly one call caused the fresh SEED artist; the other lost the DB race and
                // must report false (ArtistSeedService#addSeedIfNew's javadoc on the race-loser
                // case). Which physical thread wins is nondeterministic -- exactly one must, though.
                assertThat(List.of(r1, r2)).as("exactly one of the two racing calls reports true")
                        .containsExactlyInAnyOrder(true, false);
            }).as("neither racing call lets a DataIntegrityViolationException propagate")
                    .doesNotThrowAnyException();
        } finally {
            executor.shutdownNow();
        }

        List<Artist> rows = artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.SEED);
        assertThat(rows).as("exactly one Artist row exists after the race, not zero and not two").hasSize(1);
        assertThat(rows.get(0).getName()).isEqualTo(NAME);
        Long artistId = rows.get(0).getId();

        assertThat(artistActivatedListener.received)
                .as("ArtistActivated fired exactly once for this artist -- not zero times (dropped) "
                        + "and not twice (the race loser must not re-publish for someone else's insert)")
                .hasSize(1);
        assertThat(artistActivatedListener.received.get(0).artistId()).isEqualTo(artistId);

        List<ScanJob> scanJobs = awaitUntil(
                () -> scanJobRepository.findByOwnerAndArtistId(OWNER, artistId),
                jobs -> jobs.size() == showSources.size());
        List<ExpandJob> expandJobs = awaitUntil(
                () -> expandJobRepository.findByOwnerAndArtistId(OWNER, artistId),
                jobs -> jobs.size() == relationSources.size());

        assertThat(scanJobs).as("one scan_job per ShowSource -- the single real activation's listener effect")
                .hasSize(showSources.size());
        assertThat(expandJobs)
                .as("one expand_job per RelationSource -- the single real activation's listener effect")
                .hasSize(relationSources.size());
    }

    /**
     * Captures every {@link ArtistActivated} published during a test, synchronously and
     * unconditionally (a plain {@link EventListener}, not {@code @ApplicationModuleListener} --
     * fires immediately on {@code publishEvent}, inside the still-open publishing transaction,
     * unlike the production listeners which are {@code @Async} AFTER_COMMIT). That makes it a
     * direct, deterministic count of how many times the event itself fired -- which the production
     * listeners' own idempotent {@code insertIfAbsent} enqueue can't prove on its own, since two
     * fires for the same artist id would still only ever leave one job row per source.
     */
    @TestConfiguration
    static class CapturingListenerConfig {
        @Bean
        CapturingListener artistActivatedListener() {
            return new CapturingListener();
        }
    }

    static class CapturingListener {
        final List<ArtistActivated> received = new CopyOnWriteArrayList<>();

        @EventListener
        void on(ArtistActivated event) {
            received.add(event);
        }
    }
}
