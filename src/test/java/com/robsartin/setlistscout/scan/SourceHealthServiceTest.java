package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Clock;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #265: the flip rule, against real Postgres. Every write is an {@code INSERT ... ON CONFLICT}
 * whose new counter is computed in SQL from the row's own value, so a mock would prove nothing about
 * the behaviour that matters.
 */
@SpringBootTest
@Testcontainers
class SourceHealthServiceTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String SOURCE = "bandsintown";
    private static final int N = SourceHealthService.FAILURE_THRESHOLD;

    @Autowired
    private SourceHealthService service;

    @Autowired
    private SourceHealthRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
        service.refresh();
    }

    private static Throwable forbidden() {
        return HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null);
    }

    private static Throwable notFound() {
        return HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null, null, null);
    }

    private void failFor(long artists) {
        for (long artistId = 1; artistId <= artists; artistId++) {
            service.recordFailure(SOURCE, artistId, forbidden());
        }
    }

    @Test
    @DisplayName("N consecutive identical failures across different artists flips the source unhealthy")
    void thresholdFlipsIt() {
        failFor(N);

        assertThat(service.isHealthy(SOURCE)).isFalse();
        assertThat(repository.findById(SOURCE)).get().satisfies(h -> {
            assertThat(h.getStreakSignature()).isEqualTo("403");
            assertThat(h.getUnhealthySince()).isNotNull();
        });
    }

    @Test
    @DisplayName("N-1 failures does NOT flip it -- the threshold is a threshold")
    void oneShortOfThresholdDoesNotFlip() {
        failFor(N - 1);

        service.refresh();
        assertThat(service.isHealthy(SOURCE)).isTrue();
    }

    /**
     * The assertion that stops this becoming an over-eager kill switch, and the reason
     * {@code band-site} -- which scrapes a different host per artist -- is safe to track here.
     */
    @Test
    @DisplayName("a source failing for ONE artist while succeeding for others NEVER flips")
    void oneBadArtistNeverFlipsTheSource() {
        for (int round = 0; round < N * 3; round++) {
            service.recordFailure(SOURCE, 42L, notFound());
            service.recordSuccess(SOURCE);
        }

        service.refresh();
        assertThat(service.isHealthy(SOURCE)).isTrue();
    }

    @Test
    @DisplayName("a long streak confined to ONE artist does not flip it either -- distinct artists "
            + "are required, not just a count")
    void aStreakOnOneArtistDoesNotFlip() {
        for (int i = 0; i < N * 2; i++) {
            service.recordFailure(SOURCE, 42L, notFound());
        }

        service.refresh();
        assertThat(service.isHealthy(SOURCE)).isTrue();
        assertThat(repository.findById(SOURCE)).get()
                .satisfies(h -> assertThat(h.getStreakArtists()).isEqualTo(1));
    }

    @Test
    @DisplayName("a MIX of failure signatures never accumulates -- a bad day is not a dead source")
    void mixedSignaturesDoNotAccumulate() {
        for (long artistId = 1; artistId <= N * 2; artistId++) {
            service.recordFailure(SOURCE, artistId, artistId % 2 == 0 ? forbidden() : notFound());
        }

        service.refresh();
        assertThat(service.isHealthy(SOURCE)).isTrue();
        assertThat(repository.findById(SOURCE)).get()
                .satisfies(h -> assertThat(h.getConsecutiveFailures()).isEqualTo(1));
    }

    @Test
    @DisplayName("one success clears the streak, so the next failure starts from scratch")
    void successResetsTheStreak() {
        failFor(N - 1);
        service.recordSuccess(SOURCE);
        service.recordFailure(SOURCE, 99L, forbidden());

        service.refresh();
        assertThat(service.isHealthy(SOURCE)).isTrue();
        assertThat(repository.findById(SOURCE)).get()
                .satisfies(h -> assertThat(h.getConsecutiveFailures()).isEqualTo(1));
    }

    /**
     * The recovery path, and the reason there is no button anywhere: the 2026-08-25 outage was
     * prolonged by a manual whole-fleet re-due nobody knew to run.
     */
    @Test
    @DisplayName("an unhealthy source RECOVERS on the first successful call, with no human step")
    void recoversOnFirstSuccess() {
        failFor(N);
        assertThat(service.isHealthy(SOURCE)).isFalse();

        service.recordSuccess(SOURCE);

        assertThat(service.isHealthy(SOURCE)).isTrue();
        assertThat(repository.findById(SOURCE)).get().satisfies(h -> {
            assertThat(h.isHealthy()).isTrue();
            assertThat(h.getUnhealthySince()).isNull();
            assertThat(h.getConsecutiveFailures()).isZero();
            assertThat(h.getLastSuccessAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("health is per SOURCE -- one dead source does not take another down with it")
    void healthIsPerSource() {
        failFor(N);

        assertThat(service.isHealthy(SOURCE)).isFalse();
        assertThat(service.isHealthy("ticketmaster")).isTrue();
        assertThat(service.isHealthy("band-site")).isTrue();
    }

    /**
     * A source switched off with {@code setlistscout.sources.<id>=false} (#139) has no
     * {@link com.robsartin.setlistscout.scan.source.ShowSource} bean, so {@code ScanUnitRunner}
     * returns early without ever making the call -- it can record neither a failure nor a success,
     * and a stale unhealthy row would therefore be uncleanable. Bandsintown is switched off in
     * production right now for exactly the reason this issue exists, so this is the live case: the
     * banner must not be permanent and the jobs must not churn on the probe interval forever.
     */
    @Test
    @DisplayName("issue #265: a DISABLED source is treated as healthy -- its unhealthy row can "
            + "never be cleared, so it must not produce an undismissable banner")
    void aDisabledSourceIsTreatedAsHealthy() {
        failFor(N);
        assertThat(service.isHealthy(SOURCE)).isFalse();

        // The same stored state, seen by an app where this source is switched off.
        SourceHealthService withSourceOff = new SourceHealthService(repository, Set.of("ticketmaster"),
                Clock.systemUTC());
        withSourceOff.refresh();

        assertThat(withSourceOff.unhealthyDetail()).isEmpty();
        assertThat(withSourceOff.isHealthy(SOURCE)).isTrue();
        assertThat(withSourceOff.unhealthySources()).isEmpty();
    }

    @Test
    @DisplayName("issue #265: re-enabling the source resumes exactly where it left off -- the row "
            + "is filtered from view, never deleted")
    void reEnablingResumesFromTheStoredRow() {
        failFor(N);

        SourceHealthService withSourceOn = new SourceHealthService(repository, Set.of(SOURCE),
                Clock.systemUTC());
        withSourceOn.refresh();

        assertThat(withSourceOn.isHealthy(SOURCE)).isFalse();
        assertThat(withSourceOn.unhealthyDetail()).hasSize(1);
    }

    // ---- #273: the status board, as opposed to the alarm ----

    private SourceHealthService withEnabled(String... ids) {
        SourceHealthService svc = new SourceHealthService(repository, Set.of(ids), Clock.systemUTC());
        svc.refresh();
        return svc;
    }

    @Test
    @DisplayName("issue #273: every ENABLED source appears, including one that has never run -- "
            + "'is it working?' must be answerable when the answer is yes")
    void everyEnabledSourceAppearsIncludingOneThatNeverRan() {
        service.recordSuccess("ticketmaster");

        SourceHealthService svc = withEnabled("ticketmaster", "bandsintown", "band-site");

        assertThat(svc.allSourceStatus()).extracting(SourceStatusRow::source)
                .containsExactly("band-site", "bandsintown", "ticketmaster");
        assertThat(svc.allSourceStatus()).extracting(SourceStatusRow::state)
                .containsOnly("On");
        assertThat(svc.allSourceStatus())
                .filteredOn(r -> r.source().equals("band-site"))
                .singleElement()
                .satisfies(r -> assertThat(r.lastSuccessAt()).isNull());
    }

    /**
     * #265's stuck-row case seen from the other side: a source switched off with
     * {@code setlistscout.sources.<id>=false} (#139) can hold a stale unhealthy row forever, because
     * with no bean it can record neither a failure nor a success. Reporting that as "Down" would
     * describe a source that is not even being called.
     */
    @Test
    @DisplayName("issue #273: a DISABLED source reads Off, never Down -- even when its stored row "
            + "says unhealthy")
    void aDisabledSourceReadsOffNotDown() {
        failFor(N);
        assertThat(service.isHealthy(SOURCE)).isFalse();

        SourceHealthService svc = withEnabled("ticketmaster");

        assertThat(svc.allSourceStatus())
                .filteredOn(r -> r.source().equals(SOURCE))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.state()).isEqualTo("Off");
                    assertThat(r.enabled()).isFalse();
                });
    }

    @Test
    @DisplayName("issue #273: an enabled source past the threshold reads Down")
    void anEnabledDeadSourceReadsDown() {
        failFor(N);

        SourceHealthService svc = withEnabled(SOURCE);

        assertThat(svc.allSourceStatus()).singleElement().satisfies(r -> {
            assertThat(r.state()).isEqualTo("Down");
            assertThat(r.unhealthySince()).isNotNull();
            assertThat(r.streakSignature()).isEqualTo("403");
        });
    }

    @Test
    @DisplayName("issue #273: a source MID-STREAK still reads On, but shows the streak -- that is "
            + "exactly the moment an operator would want to look")
    void aSourceMidStreakReadsOnButShowsTheStreak() {
        failFor(N - 1);

        SourceHealthService svc = withEnabled(SOURCE);

        assertThat(svc.allSourceStatus()).singleElement().satisfies(r -> {
            assertThat(r.state()).isEqualTo("On");
            assertThat(r.consecutiveFailures()).isEqualTo(N - 1);
            assertThat(r.streakSignature()).isEqualTo("403");
            assertThat(r.lastFailureAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("issue #273: a stored row for a source that is neither enabled nor known still "
            + "appears -- it must not vanish from the page the moment someone disables it")
    void aStoredRowForADisabledSourceStillAppears() {
        failFor(N);

        SourceHealthService svc = withEnabled("ticketmaster");

        assertThat(svc.allSourceStatus()).extracting(SourceStatusRow::source)
                .containsExactly(SOURCE, "ticketmaster");
    }
}
