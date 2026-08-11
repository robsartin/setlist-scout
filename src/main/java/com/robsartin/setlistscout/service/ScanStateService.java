package com.robsartin.setlistscout.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks whether a show scan is in flight, per owner (the app is multi-tenant).
 *
 * <p>A scan is represented by its start instant in {@link #running}. {@link #tryStart} atomically
 * claims the slot so a second "Scan now" for an owner whose scan is already running is ignored,
 * and {@link #dots} derives the growing "Scanning..." dot count from elapsed time (one dot per
 * ~10 seconds) without needing a separate tick counter.
 */
@Component
public class ScanStateService {

    /** How much wall-clock time buys one more dot in the "Scanning..." indicator. */
    private static final long SECONDS_PER_DOT = 10;

    private final Map<String, Instant> running = new ConcurrentHashMap<>();
    private final Clock clock;

    public ScanStateService() {
        this(Clock.systemUTC());
    }

    // Package-private: lets tests drive dot growth with a controllable clock.
    ScanStateService(Clock clock) {
        this.clock = clock;
    }

    /**
     * Atomically claim the scan slot for this owner.
     *
     * @return {@code true} if a scan was started; {@code false} if one is already running (the
     * caller should not start a duplicate).
     */
    public boolean tryStart(String owner) {
        return running.putIfAbsent(owner, clock.instant()) == null;
    }

    /** Mark this owner's scan as finished, freeing the slot for a later scan. */
    public void finish(String owner) {
        running.remove(owner);
    }

    public boolean isRunning(String owner) {
        return running.containsKey(owner);
    }

    /** Number of trailing dots to show: 0 at the start, then +1 roughly every 10 seconds. */
    public int dots(String owner) {
        Instant started = running.get(owner);
        if (started == null) {
            return 0;
        }
        long elapsedSeconds = Duration.between(started, clock.instant()).getSeconds();
        return (int) (elapsedSeconds / SECONDS_PER_DOT);
    }
}
