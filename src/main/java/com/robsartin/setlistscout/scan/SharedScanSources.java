package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.shared.SharedScanOwner;

import java.util.List;
import java.util.Set;

/**
 * Which show sources an owner's scan jobs are enqueued for (#163).
 * <p>
 * One definition, used by BOTH enqueue paths -- {@code ScanJobListener} (on activation) and
 * {@code ScanJobBackfill} (on every application start). The backfill reaches
 * {@code scanJobRepository.insertIfAbsent} without going through the listener, so a policy that
 * lived only in the listener would be re-defeated on every restart.
 */
final class SharedScanSources {

    /**
     * The sources a shared scan may use. An allow-list, not a deny-list, so a source added later is
     * excluded by default rather than silently joining shared scans. band-site is the one
     * deliberately left out: it scrapes and falls back to {@code TourPageLlmService}, which is
     * billed per artist, and a shared scan's artists are already covered by both participants'
     * own scans.
     */
    private static final Set<String> SHARED_SCAN_SOURCE_IDS = Set.of("ticketmaster", "bandsintown");

    private SharedScanSources() {
    }

    /** Every source for a real owner; only the cheap ones for a shared scan. */
    static List<ShowSource> forOwner(String owner, List<ShowSource> all) {
        if (!SharedScanOwner.isSharedScanKey(owner)) {
            return all;
        }
        return all.stream().filter(source -> SHARED_SCAN_SOURCE_IDS.contains(source.id())).toList();
    }
}
