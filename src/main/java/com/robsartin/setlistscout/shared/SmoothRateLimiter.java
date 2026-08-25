package com.robsartin.setlistscout.shared;

import java.util.function.LongSupplier;

/**
 * Paces callers to a fixed rate by spacing permits evenly, blocking the caller until its turn.
 *
 * <p>Issue #263: Ticketmaster rejected roughly 5% of scans with
 * {@code "Spike arrest violation. Allowed rate : MessageRate{messagesPerPeriod=5,
 * periodInMicroseconds=1000000, maxBurstMessageCount=1.0}"}, and nothing in the app respected that
 * ceiling. {@code ScanPoller} claims a batch (20 by default) and runs it in a plain loop, so twenty
 * sequential HTTP calls land as fast as the network returns them -- usually just under 5/sec,
 * intermittently just over. That is exactly the shape of the observed failures: sporadic, one at a
 * time, spread across the day rather than arriving in bursts.
 *
 * <h2>Smooth, not bucketed</h2>
 * Deliberately does NOT bank permits during idle time. A token bucket that accrued five permits
 * over a quiet second and then released them together would still be rejected -- the quoted policy
 * sets {@code maxBurstMessageCount=1.0}, so Ticketmaster permits no burst at all. Evenly spacing
 * every permit is the only shape that satisfies it.
 *
 * <h2>Testability</h2>
 * The clock and the sleep are injected so a test can assert the exact schedule against a fake
 * clock. A limiter verified against wall time either sleeps for real (slow) or asserts loose
 * bounds (flaky); neither pins the behaviour.
 *
 * <p>Thread-safe: {@link #acquire()} is synchronized, so concurrent callers queue and are spaced
 * against the same shared schedule rather than each pacing itself independently.
 */
public final class SmoothRateLimiter {

    /** Sleeps the current thread for {@code nanos}; the seam a test replaces to avoid real waits. */
    @FunctionalInterface
    public interface Sleeper {
        void sleep(long nanos);
    }

    private final long minIntervalNanos;
    private final LongSupplier nanoTime;
    private final Sleeper sleeper;

    /** Next instant at which a permit may be issued; before this, a caller waits. */
    private long nextFreeNanos = Long.MIN_VALUE;

    public SmoothRateLimiter(int permitsPerSecond) {
        this(permitsPerSecond, System::nanoTime, SmoothRateLimiter::sleepNanos);
    }

    /**
     * Deterministic constructor: supply the clock and the sleep. Public rather than
     * package-private because callers in other packages need it to prove they actually acquire a
     * permit -- {@code TicketmasterServiceTest} pins exactly that, after a mutation showed that
     * deleting the {@code acquire()} call failed no test at all.
     */
    public SmoothRateLimiter(int permitsPerSecond, LongSupplier nanoTime, Sleeper sleeper) {
        if (permitsPerSecond <= 0) {
            throw new IllegalArgumentException("permitsPerSecond must be positive, was " + permitsPerSecond);
        }
        this.minIntervalNanos = 1_000_000_000L / permitsPerSecond;
        this.nanoTime = nanoTime;
        this.sleeper = sleeper;
    }

    /**
     * Blocks until this caller may proceed. An idle limiter returns immediately; a caller arriving
     * inside the interval waits out the remainder.
     */
    public synchronized void acquire() {
        long now = nanoTime.getAsLong();
        // Long.MIN_VALUE marks "never issued": the first caller is always free, and comparing
        // against it directly would overflow when the interval is added below.
        long wait = nextFreeNanos == Long.MIN_VALUE ? 0L : Math.max(0L, nextFreeNanos - now);
        // Only a real wait is handed to the sleeper: a no-op sleep(0) would be indistinguishable
        // in a test from an actual pause, and the distinction is the whole point of the class.
        if (wait > 0) {
            sleeper.sleep(wait);
        }
        nextFreeNanos = now + wait + minIntervalNanos;
    }

    private static void sleepNanos(long nanos) {
        if (nanos <= 0) {
            return;
        }
        try {
            Thread.sleep(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
