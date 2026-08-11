package com.robsartin.setlistscout.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ScanStateServiceTest {

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";

    /** A hand-cranked clock so dot growth is deterministic. */
    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now;

        MutableClock(Instant start) {
            this.now = new AtomicReference<>(start);
        }

        void advance(Duration by) {
            now.updateAndGet(instant -> instant.plus(by));
        }

        @Override public Instant instant() { return now.get(); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
    }

    @Test
    void tryStartSucceedsOnceThenGuardsAgainstOverlap() {
        ScanStateService state = new ScanStateService();

        assertThat(state.tryStart(ALICE)).isTrue();
        assertThat(state.isRunning(ALICE)).isTrue();
        // A second "Scan now" while one is running must be ignored.
        assertThat(state.tryStart(ALICE)).isFalse();
    }

    @Test
    void finishClearsStateAndAllowsANewScan() {
        ScanStateService state = new ScanStateService();
        state.tryStart(ALICE);

        state.finish(ALICE);

        assertThat(state.isRunning(ALICE)).isFalse();
        assertThat(state.tryStart(ALICE)).isTrue();
    }

    @Test
    void stateIsTrackedPerOwner() {
        ScanStateService state = new ScanStateService();
        state.tryStart(ALICE);

        assertThat(state.isRunning(BOB)).isFalse();
        // Bob can start his own scan even while Alice's is running.
        assertThat(state.tryStart(BOB)).isTrue();
    }

    @Test
    void dotsGrowByOneRoughlyEveryTenSeconds() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-11T00:00:00Z"));
        ScanStateService state = new ScanStateService(clock);
        state.tryStart(ALICE);

        assertThat(state.dots(ALICE)).isZero();          // t = 0s -> "Scanning"
        clock.advance(Duration.ofSeconds(10));
        assertThat(state.dots(ALICE)).isEqualTo(1);      // t = 10s -> "Scanning."
        clock.advance(Duration.ofSeconds(15));
        assertThat(state.dots(ALICE)).isEqualTo(2);      // t = 25s -> "Scanning.."
    }

    @Test
    void dotsAreZeroWhenNoScanIsRunning() {
        ScanStateService state = new ScanStateService();
        assertThat(state.dots(ALICE)).isZero();
    }
}
