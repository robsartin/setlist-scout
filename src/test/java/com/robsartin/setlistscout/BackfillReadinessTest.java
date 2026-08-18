package com.robsartin.setlistscout;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.scan.ScanJobRepository;
import com.robsartin.setlistscout.scan.source.ShowSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #195: a REAL production incident, not a hypothetical -- {@code /actuator/health} returned
 * 503 {@code OUT_OF_SERVICE} for ~23 minutes after every restart against the real 1,742-artist
 * catalog, because {@code scan.ScanJobBackfill}/{@code expansion.ExpandJobBackfill} were
 * {@code ApplicationRunner}s: Boot's {@code callRunners()} blocks {@code ApplicationReadyEvent}
 * (and therefore {@code ReadinessState.ACCEPTING_TRAFFIC}) until every runner returns, and these
 * two did ~14,000 individual inserts on a 0.1-CPU instance.
 * <p>
 * A test against an empty database, or one that invokes the backfill directly on an
 * already-booted context, cannot reproduce this: readiness is a one-way latch Boot flips once,
 * during boot -- calling a bean method later can never un-flip it. The only faithful reproduction
 * is a REAL boot with a realistic backlog already sitting in the database, exactly the "instance
 * restarts, pays the cost again" shape the issue's own production logs show. So this drives two
 * independent {@link SpringApplicationBuilder} boots against the same Testcontainers Postgres: one
 * to seed a 2,000-artist backlog (comfortably past the 1,742 measured in production) with no jobs
 * -- artists that "predate the job tables", same shape as {@code ScanJobBackfillTest}, just at
 * production scale -- and a second, timed boot (the real subject under test) to prove
 * {@code /actuator/health} reports UP within seconds of the port binding, not minutes, while that
 * backlog is reconciled in the background.
 */
@Testcontainers
class BackfillReadinessTest {

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "readiness-backlog@example.com";
    private static final int ARTIST_COUNT = 2000;

    /**
     * Bound the health-UP wait to "seconds" -- the whole point of #195. Comfortably above what a
     * healthy boot needs (residual DispatcherServlet/security wiring after the port binds) and
     * comfortably below "minutes", so a regression back to synchronous blocking fails loudly
     * rather than just slowly passing.
     */
    private static final Duration HEALTH_UP_BOUND = Duration.ofSeconds(30);

    /** Generous bound for the backlog to actually finish reconciling in the background. */
    private static final Duration RECONCILE_BOUND = Duration.ofSeconds(90);

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @Test
    @DisplayName("issue #195: neither backfill implements ApplicationRunner -- Boot's callRunners() "
            + "structurally cannot block readiness on them")
    void backfillsDoNotImplementApplicationRunner() {
        assertThat(ApplicationRunner.class.isAssignableFrom(com.robsartin.setlistscout.scan.ScanJobBackfill.class))
                .as("ScanJobBackfill must not re-enter Boot's callRunners() -- that's the #195 bug")
                .isFalse();
        assertThat(ApplicationRunner.class.isAssignableFrom(com.robsartin.setlistscout.expansion.ExpandJobBackfill.class))
                .as("ExpandJobBackfill must not re-enter Boot's callRunners() -- that's the #195 bug")
                .isFalse();
    }

    @Test
    @DisplayName("issue #195: /actuator/health reports UP within seconds of a real boot even with a "
            + "realistic 2,000-artist backlog still being reconciled -- and that backlog still "
            + "genuinely gets its scan_job/expand_job rows")
    void healthIsUpQuicklyWhileARealisticBacklogReconciles() throws Exception {
        Map<String, Object> connectionProps = Map.of(
                "DATABASE_URL", postgres.getJdbcUrl(),
                "DATABASE_USERNAME", postgres.getUsername(),
                "DATABASE_PASSWORD", postgres.getPassword(),
                "GOOGLE_CLIENT_ID", "test-client-id",
                "GOOGLE_CLIENT_SECRET", "test-client-secret",
                // application.yml defines server.port as ${PORT:8080} -- a real value, even
                // though it's placeholder syntax, at higher precedence than these
                // SpringApplicationBuilder "default properties". Overriding server.port directly
                // here would lose to that; PORT is the env-var-shaped key application.yml itself
                // interpolates (matching how Render wires it -- see render.yaml), and nothing
                // else defines that key, so it wins outright.
                "PORT", "0"
        );

        // Phase 1: a throwaway boot, purely to run Flyway and seed a realistic backlog of active
        // artists with NO scan_job/expand_job rows -- exactly the artists "predate the job tables"
        // shape ScanJobBackfillTest already covers at small scale, here at production scale.
        seedRealisticBacklog(connectionProps);

        // Phase 2: the real, timed boot under test -- the backfill beans are enabled (default).
        ExecutorService bootExecutor = Executors.newSingleThreadExecutor();
        CompletableFuture<Integer> portFuture = new CompletableFuture<>();
        ApplicationListener<ApplicationEvent> capturePort = event -> {
            if (event instanceof WebServerInitializedEvent ready) {
                portFuture.complete(ready.getWebServer().getPort());
            }
        };
        SpringApplicationBuilder builder = new SpringApplicationBuilder(SetlistScoutApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(connectionProps)
                .listeners(capturePort);

        Future<ConfigurableApplicationContext> contextFuture = bootExecutor.submit(() -> builder.run());
        ConfigurableApplicationContext context = null;
        try {
            int port = portFuture.get(60, TimeUnit.SECONDS);
            Instant portBoundAt = Instant.now();

            HttpResponse<String> health = awaitUntil(
                    () -> tryGetHealth(port), BackfillReadinessTest::isUp, HEALTH_UP_BOUND);
            Duration elapsed = Duration.between(portBoundAt, Instant.now());

            assertThat(health)
                    .as("never got a single response from /actuator/health on port " + port
                            + " within " + elapsed)
                    .isNotNull();
            assertThat(health.body())
                    .as("health body " + elapsed + " after the port bound -- must be UP within "
                            + "seconds, not minutes (#195 measured ~23 minutes against a comparable "
                            + "1,742-artist catalog): " + health.body())
                    .contains("\"status\":\"UP\"");

            // The backlog still genuinely reconciles -- proves this is "don't gate readiness on
            // it", not "skip the work". Bounded generously: it now runs on a background scheduler
            // thread, so it may still be mid-flight when health already reported UP above.
            context = contextFuture.get(120, TimeUnit.SECONDS);
            ScanJobRepository scanJobRepository = context.getBean(ScanJobRepository.class);
            ExpandJobRepository expandJobRepository = context.getBean(ExpandJobRepository.class);
            int showSourceCount = context.getBeansOfType(ShowSource.class).size();
            int relationSourceCount = context.getBeansOfType(RelationSource.class).size();

            List<?> scanJobs = awaitUntil(() -> scanJobRepository.findByOwner(OWNER),
                    jobs -> jobs.size() == ARTIST_COUNT * showSourceCount, RECONCILE_BOUND);
            assertThat(scanJobs)
                    .as("one scan_job per ShowSource for every backlog artist -- reconciliation, "
                            + "not just fast readiness")
                    .hasSize(ARTIST_COUNT * showSourceCount);

            List<?> expandJobs = awaitUntil(() -> expandJobRepository.findByOwner(OWNER),
                    jobs -> jobs.size() == ARTIST_COUNT * relationSourceCount, RECONCILE_BOUND);
            assertThat(expandJobs)
                    .as("one expand_job per RelationSource for every backlog artist (all SEED, so "
                            + "including tribute) -- reconciliation, not just fast readiness")
                    .hasSize(ARTIST_COUNT * relationSourceCount);
        } finally {
            if (context != null) {
                context.close();
            }
            bootExecutor.shutdownNow();
        }
    }

    private void seedRealisticBacklog(Map<String, Object> connectionProps) {
        Map<String, Object> seedOnlyProps = new HashMap<>(connectionProps);
        // This throwaway boot's own backfill pass would otherwise see an empty artist table
        // anyway (near-instant no-op) -- disabled purely so it doesn't do any of the real work
        // early, keeping phase 2 the sole subject under test.
        seedOnlyProps.put("setlistscout.job-backfill-enabled", "false");

        ConfigurableApplicationContext seedContext = new SpringApplicationBuilder(SetlistScoutApplication.class)
                .web(WebApplicationType.SERVLET)
                .properties(seedOnlyProps)
                .run();
        try {
            ArtistRepository artistRepository = seedContext.getBean(ArtistRepository.class);
            List<Artist> backlog = new ArrayList<>(ARTIST_COUNT);
            for (int i = 0; i < ARTIST_COUNT; i++) {
                Artist artist = new Artist("Readiness Backlog Artist " + i, ArtistSource.SEED_LIST,
                        ArtistStatus.SEED, null, null);
                artist.setOwner(OWNER);
                backlog.add(artist);
            }
            artistRepository.saveAll(backlog);
        } finally {
            seedContext.close();
        }
    }

    private HttpResponse<String> tryGetHealth(int port) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://localhost:" + port + "/actuator/health"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            return client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception notReadyYet) {
            // Connection refused / reset while Tomcat or the servlet/security chain is still
            // wiring up -- not a failure, just "not up yet"; keep polling.
            return null;
        }
    }

    private static boolean isUp(HttpResponse<String> response) {
        return response != null && response.statusCode() == 200 && response.body().contains("\"status\":\"UP\"");
    }

    /**
     * Bounded manual poll, no fixed sleep -- mirrors {@code AbstractPostgresIntegrationTest#poll}.
     * This class doesn't extend that base (it manages its own pair of hand-built
     * {@link SpringApplicationBuilder} contexts rather than a single {@code @SpringBootTest}
     * context, so that base class's {@code @DynamicPropertySource}/single-context assumptions
     * don't fit). Returns the last fetched value regardless of whether {@code condition} was ever
     * satisfied, so a timeout still fails with a useful diff.
     */
    private static <T> T awaitUntil(Supplier<T> fetch, Predicate<T> condition, Duration timeout)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        T last = fetch.get();
        while (!condition.test(last) && Instant.now().isBefore(deadline)) {
            Thread.sleep(200);
            last = fetch.get();
        }
        return last;
    }
}
