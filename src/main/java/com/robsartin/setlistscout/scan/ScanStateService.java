package com.robsartin.setlistscout.scan;

import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks whether a show scan is in flight, per owner (the app is multi-tenant).
 *
 * <p>{@link #tryStart} atomically claims the slot so a second "Scan now" for an owner whose scan
 * is already running is ignored; the "Scanning..." indicator is a static label driven off
 * {@link #isRunning}.
 */
@Component
public class ScanStateService {

    private final Set<String> running = ConcurrentHashMap.newKeySet();

    /**
     * Atomically claim the scan slot for this owner.
     *
     * @return {@code true} if a scan was started; {@code false} if one is already running (the
     * caller should not start a duplicate).
     */
    public boolean tryStart(String owner) {
        return running.add(owner);
    }

    /** Mark this owner's scan as finished, freeing the slot for a later scan. */
    public void finish(String owner) {
        running.remove(owner);
    }

    public boolean isRunning(String owner) {
        return running.contains(owner);
    }
}
