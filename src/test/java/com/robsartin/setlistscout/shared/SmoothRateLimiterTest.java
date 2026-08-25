package com.robsartin.setlistscout.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #263. Deliberately driven by a fake clock and a recording sleeper rather than wall time:
 * a limiter tested against the real clock either sleeps for real (slow) or asserts loose bounds
 * (flaky), and neither pins the schedule the way asserting the exact computed waits does.
 */
class SmoothRateLimiterTest {

    /** Nanos handed to sleep(), in order. */
    private final List<Long> slept = new ArrayList<>();
    private long now;

    private SmoothRateLimiter limiter(int permitsPerSecond) {
        return new SmoothRateLimiter(permitsPerSecond, () -> now, nanos -> {
            slept.add(nanos);
            now += nanos;
        });
    }

    @Test
    @DisplayName("the first permit is immediate -- an idle limiter never delays the first caller")
    void firstPermitIsImmediate() {
        limiter(5).acquire();
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("back-to-back permits are spaced to the configured rate: 5/sec means 200ms apart")
    void spacesBackToBackPermits() {
        SmoothRateLimiter limiter = limiter(5);
        for (int i = 0; i < 4; i++) {
            limiter.acquire();
        }
        assertThat(slept).as("first is free; each subsequent waits a full 200ms")
                .containsExactly(200_000_000L, 200_000_000L, 200_000_000L);
    }

    @Test
    @DisplayName("smooths rather than bursts -- Ticketmaster's spike arrest allows maxBurst 1.0, so "
            + "a bucket that accrued 5 permits and released them at once would still be rejected")
    void doesNotAllowABurstAfterIdleTime() {
        SmoothRateLimiter limiter = limiter(5);
        limiter.acquire();
        now += Duration.ofSeconds(10).toNanos();   // long idle
        limiter.acquire();
        limiter.acquire();
        assertThat(slept).as("idle time does not bank permits; the back-to-back call still waits")
                .containsExactly(200_000_000L);
    }

    @Test
    @DisplayName("a caller that arrives after the interval has already elapsed does not wait")
    void doesNotDelayAWellSpacedCaller() {
        SmoothRateLimiter limiter = limiter(5);
        limiter.acquire();
        now += 250_000_000L;
        limiter.acquire();
        assertThat(slept).isEmpty();
    }

    @Test
    @DisplayName("rate is configurable -- 2/sec spaces permits 500ms apart")
    void honoursTheConfiguredRate() {
        SmoothRateLimiter limiter = limiter(2);
        limiter.acquire();
        limiter.acquire();
        assertThat(slept).containsExactly(500_000_000L);
    }

    @Test
    @DisplayName("rejects a non-positive rate rather than dividing by zero at the first call")
    void rejectsNonPositiveRate() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> limiter(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
