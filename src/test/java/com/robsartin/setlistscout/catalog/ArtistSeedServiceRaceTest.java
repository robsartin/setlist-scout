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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Real-path Testcontainers proof of issue #133: {@link ArtistSeedService#addSeedIfNew}'s
 * "not found" branch used to be a check-then-insert race on the real {@code artist (owner, name)}
 * unique constraint -- two near-simultaneous calls for the same brand-new name could both pass the
 * {@link ArtistNameMatcher} pre-check, then one would throw {@code DataIntegrityViolationException}
 * on flush. This actually happened in production: {@code ERROR: duplicate key value violates
 * unique constraint "ukr03a96lqhsb7djq2tn4rq60vn" Detail: Key (name)=(Nebraska) already exists.}
 * <p>
 * That pre-check is gone as of #179: {@code addSeedIfNew} inserts first and lets
 * {@code UNIQUE (owner, normalized_name)} decide. Which is why this test no longer forces an
 * interleave with a spy, and no longer needs to. The old version had to: without forcing, the
 * second thread's pre-check would simply find the first thread's committed row and take the
 * "already exists" branch, never reaching the race at all. Now there is no branch before the
 * write -- both calls reach {@code insertIfAbsent} unconditionally, whatever the timing -- so the
 * same production path runs whether or not the two INSERTs physically overlap inside Postgres: one
 * returns 1, the other is absorbed by {@code ON CONFLICT} and returns 0. A {@link CyclicBarrier}
 * releases both threads together so they DO contend for real on two connections, but every
 * assertion below holds either way. That is precisely what moving the guard into the database
 * buys, and a test that passes regardless of interleaving is the honest way to state it.
 * <p>
 * Everything here is production wiring against a real Postgres, not a mock -- the real repository,
 * the real transaction, the real {@link ArtistActivationService}, and the real
 * {@code ArtistActivated} publish (a Modulith {@code Scenario} test would be a false green here:
 * see CLAUDE.md's invariant on that).
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

    @Autowired
    private CapturingListener artistActivatedListener;

    @BeforeEach
    void setUp() {
        // Shared container/context across this class's test methods (see
        // AbstractPostgresIntegrationTest) -- clear first so a prior method's committed rows don't
        // collide on (owner, normalized_name).
        artistRepository.deleteAll();
        artistActivatedListener.received.clear();
    }

    @Test
    @DisplayName("issue #133/#179: two concurrent addSeedIfNew calls for the same brand-new name race "
            + "at the DB's (owner, normalized_name) unique constraint -- neither call lets an "
            + "exception propagate, exactly one Artist row is created, and ArtistActivated fires "
            + "exactly once (so jobs are enqueued exactly once, not zero or twice)")
    void concurrentAddSeedIfNewForTheSameNewNameRacesSafely() throws Exception {
        // Forces the real interleave: both threads must be inside insertIfAbsent, in their own
        // open transactions, BEFORE either one's INSERT actually runs -- so they race for real at
        // the (owner, normalized_name) unique constraint. Not a mocked repository (the real query
        // runs, via callRealMethod) and not a mocked constraint: a real Postgres constraint across
        // two real concurrent transactions.
        // Both threads block here until the other arrives, so they enter addSeedIfNew -- and
        // therefore their two transactions and their two INSERTs -- as close to simultaneously as
        // two threads can be made to.
        //
        // Every bound below reuses AWAIT_TIMEOUT (AbstractPostgresIntegrationTest) instead of a
        // bespoke short number. This is a positive wait -- the barrier releases, and both futures
        // complete, the moment the race resolves -- so a generous bound costs nothing on the happy
        // path and only ever bounds the FAILURE path (see that constant's Javadoc). #199: this
        // test's own hardcoded 10s bounds flaked on a loaded shared CI runner (11m44s build vs
        // ~8min locally) -- exactly the contention AWAIT_TIMEOUT was raised 30s -> 90s to survive
        // elsewhere, and that reasoning was never carried over here.
        CyclicBarrier startTogether = new CyclicBarrier(2);
        Callable<Boolean> addSeed = () -> {
            startTogether.await(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return artistSeedService.addSeedIfNew(OWNER, NAME);
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        boolean[] outcomes = new boolean[2];
        try {
            Future<Boolean> call1 = executor.submit(addSeed);
            Future<Boolean> call2 = executor.submit(addSeed);

            // Split from the "exactly one true" check below on purpose (#199). This block's only
            // job is proving neither call THROWS -- in particular that no
            // DataIntegrityViolationException escapes addSeedIfNew's ON CONFLICT guard, the
            // #133/#179 regression this test exists to catch. Previously the "exactly one true"
            // assertion lived inside this same assertThatCode, so an ordinary assertion failure,
            // a plain TimeoutException, and that real bug all surfaced at this one line under this
            // one label, indistinguishable from each other. Now the only way to land here is a
            // genuine throw from the barrier rendezvous or the racing call itself, so whatever
            // AssertJ reports below IS the actual exception -- its type and message name the
            // failure directly, nothing to dig through.
            assertThatCode(() -> {
                outcomes[0] = call1.get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
                outcomes[1] = call2.get(AWAIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            }).as("neither racing call lets an exception escape addSeedIfNew (in particular, no "
                    + "DataIntegrityViolationException from the ON CONFLICT race)")
                    .doesNotThrowAnyException();
        } finally {
            executor.shutdownNow();
        }

        // A separate claim from the block above (#199): this is about the two return VALUES, not
        // about whether either call threw, so it only runs once both calls are already known to
        // have completed without throwing. Exactly one call caused the fresh SEED artist; the
        // other lost the DB race and must report false (ArtistSeedService#addSeedIfNew's javadoc
        // on the race-loser case). Which physical thread wins is nondeterministic -- exactly one
        // must, though.
        assertThat(List.of(outcomes[0], outcomes[1]))
                .as("exactly one of the two racing calls reports true")
                .containsExactlyInAnyOrder(true, false);

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
