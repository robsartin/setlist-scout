package com.robsartin.setlistscout.support;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Shared base for integration tests that boot a Spring context against a real Postgres
 * (#94 T4 dedup: was 18x copy-pasted {@code @Container @ServiceConnection PostgreSQLContainer}
 * declarations, ~14 of which also copy-pasted the OAuth stub below, and 2 of which also
 * copy-pasted {@link #awaitUntil}).
 *
 * <p>The OAuth client registration stub is here, because every subclass boots a full (or
 * MockMvc-sliced) {@code @SpringBootTest} context, and Spring Security's OAuth2 client
 * registration needs a client-id/secret to initialise -- application.yml has no default.
 *
 * <p>Each subclass still declares its own {@code @Container @ServiceConnection
 * PostgreSQLContainer} field -- deliberately NOT hoisted up here as one shared static instance.
 * A single container shared across every subclass was tried and reverted: Spring's test context
 * cache keeps one {@code ApplicationContext} (and HikariCP pool) alive per distinct
 * configuration, and this suite has enough distinct {@code @SpringBootTest} shapes
 * (plain / {@code @AutoConfigureMockMvc} / {@code RANDOM_PORT} / {@code MOCK} /
 * {@code @TestPropertySource}) that several stayed cached simultaneously against ONE Postgres
 * instance -- exhausting its connection budget and timing out the bounded {@link #awaitUntil}
 * polls in {@code PollerFlowTest} under load. Keeping one container per class matches today's
 * behavior exactly and carries none of that contention risk; it just doesn't collapse the
 * container line itself into one shared field.
 */
public abstract class AbstractPostgresIntegrationTest {

    @DynamicPropertySource
    static void oauthProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.client.registration.google.client-id", () -> "test-client-id");
        registry.add("spring.security.oauth2.client.registration.google.client-secret", () -> "test-client-secret");
    }

    /**
     * Deadline for {@link #awaitUntil}. Generous on purpose: it bounds only the FAILURE path --
     * the poll below returns the moment the condition holds, so a longer timeout costs nothing
     * when things work. It was 10s, which flaked on CI (`PollerFlowTest.expandHappyPath`): a
     * shared runner starting a Postgres container per test class, with Hikari pools contending
     * across the cached Spring contexts, can take well over 10s for an {@code @Async} AFTER_COMMIT
     * listener to fire and commit. That is slow, not broken -- so wait longer rather than fail a
     * green build. Raised again 30s -> 90s after `maxParallelForks=4` (#129) multiplied that same
     * contention across concurrently running test JVMs, reflaking the same test on CI.
     */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Bounded manual poll -- no fixed sleep -- for an async effect (e.g. an
     * {@code @ApplicationModuleListener}'s durable write) to land. Awaitility isn't a project
     * dependency; this is a plain poll-loop helper. Returns the last fetched value regardless of
     * whether {@code condition} was ever satisfied, so a timeout still fails with a useful diff
     * instead of a bare "empty" assertion.
     */
    protected static <T> T awaitUntil(Supplier<T> fetch, Predicate<T> condition) {
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
