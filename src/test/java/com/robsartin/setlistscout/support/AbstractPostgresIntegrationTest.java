package com.robsartin.setlistscout.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
     * <p>
     * {@code protected}, not {@code private}: subclasses with their own positive-wait bounds
     * outside {@link #awaitUntil} (e.g. a race test's barrier rendezvous and {@code Future#get},
     * #199) should reuse this constant and its reasoning rather than inventing a shorter one.
     */
    protected static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Deadline for proving a NEGATIVE -- "this must never be enqueued", "this must never appear".
     * Deliberately short, and deliberately NOT {@link #AWAIT_TIMEOUT}: a negative assertion's poll
     * can only be satisfied when the behaviour under test is broken, so on every passing run it is
     * guaranteed to run the deadline out in full. A generous timeout is free on the happy path for
     * a positive wait and pure cost for a negative one. 10s is ample here -- an unguarded listener
     * enqueues within the same transaction commit, not eventually.
     */
    private static final Duration NEGATIVE_PROOF_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Bounded manual poll -- no fixed sleep -- for an async effect (e.g. an
     * {@code @ApplicationModuleListener}'s durable write) to land. Awaitility isn't a project
     * dependency; this is a plain poll-loop helper. Returns the last fetched value regardless of
     * whether {@code condition} was ever satisfied, so a timeout still fails with a useful diff
     * instead of a bare "empty" assertion.
     */
    protected static <T> T awaitUntil(Supplier<T> fetch, Predicate<T> condition) {
        return poll(fetch, condition, AWAIT_TIMEOUT);
    }

    /**
     * Bounded poll for an effect that must NOT happen. Returns as soon as {@code appears} becomes
     * true (so a genuine regression fails fast with real data to assert on), and otherwise gives up
     * after {@link #NEGATIVE_PROOF_TIMEOUT} rather than {@link #AWAIT_TIMEOUT}. Callers assert the
     * absence on the returned value.
     */
    protected static <T> T awaitAbsence(Supplier<T> fetch, Predicate<T> appears) {
        return poll(fetch, appears, NEGATIVE_PROOF_TIMEOUT);
    }

    /**
     * Blocks until every {@code @ApplicationModuleListener} triggered so far has actually
     * finished -- not just the one effect a particular test happened to check for.
     * <p>
     * Exists because a {@code @BeforeEach} that calls {@code someRepository.deleteAll()} races any
     * listener still working from the PREVIOUS test: {@code @ApplicationModuleListener} is
     * {@code @Async} AFTER_COMMIT, so publishing an event (directly, or as a side effect of
     * something like {@code SharedScanReconciler#reconcile}) hands the actual DB writes to a
     * background thread that keeps running well after the triggering test method returns. If that
     * listener is still inside its own transaction when the next test's {@code deleteAll()} runs,
     * the two race for real locks on the same rows -- {@code deleteAll()} can block for as long as
     * the listener's transaction stays open and, under CI load, lose outright with
     * {@code CannotAcquireLockException}. That is not corruption -- {@code deleteAll()} is
     * asserting against (and trying to delete under) a database another thread is still writing
     * to -- so the fix is to stop racing it, not to retry the delete and hope.
     * <p>
     * Spring Modulith already durably records this for us: every publication gets a row in
     * {@code event_publication}, and the framework stamps {@code completion_date} the instant the
     * listener returns successfully. A zero count of incomplete rows is therefore direct,
     * mechanism-agnostic proof that every listener triggered so far -- not just the one whose
     * effect a narrower {@link #awaitUntil}/{@link #awaitAbsence} call happened to observe -- has
     * committed and is done. Waiting on that targets the actual race at its source instead of
     * papering over the symptom.
     * <p>
     * Call this before tearing down state in any test whose methods trigger
     * {@code @ApplicationModuleListener} work, directly or via
     * {@code ApplicationEventPublisher#publishEvent} -- even one that already does its own
     * {@link #awaitUntil}/{@link #awaitAbsence} on a specific effect, since that only proves ONE
     * listener reached its expected state, not that every listener subscribed to the same event
     * has finished. Uses {@link #AWAIT_TIMEOUT}, not a shorter deadline: this is a positive wait
     * (see that constant's own Javadoc), so a generous timeout costs nothing on the happy path,
     * where the count is already zero and this returns on the first poll.
     */
    protected static Long awaitQuiescence(JdbcTemplate jdbcTemplate) {
        return poll(
                () -> jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_publication WHERE completion_date IS NULL", Long.class),
                incomplete -> incomplete != null && incomplete == 0L,
                AWAIT_TIMEOUT);
    }

    /**
     * Shared bounded poll loop behind {@link #awaitUntil} and {@link #awaitAbsence} -- identical
     * mechanics, different deadline per caller. No fixed sleep; returns the last fetched value
     * regardless of whether {@code condition} was ever satisfied, so a timeout still fails with a
     * useful diff instead of a bare "empty" assertion.
     */
    private static <T> T poll(Supplier<T> fetch, Predicate<T> condition, Duration timeout) {
        Instant deadline = Instant.now().plus(timeout);
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

    /**
     * How many ELEMENTS carry the autofocus attribute. Counting raw substrings would read
     * Thymeleaf's `autofocus="autofocus"` (the expanded form it serialises boolean attributes to)
     * as two, so this matches the attribute on a tag instead.
     */
    protected static int countAutofocusElements(String body) {
        Matcher m = Pattern.compile("<[^>]*\\bautofocus\\b[^>]*>").matcher(body);
        int count = 0;
        while (m.find()) {
            count++;
        }
        return count;
    }
}
