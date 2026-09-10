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
}
