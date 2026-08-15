package com.robsartin.setlistscout.shared.observability;

import org.slf4j.MDC;

/**
 * Correlation field name(s) shared across the request-scoped correlation path. {@link #CID} is
 * stamped into SLF4J's MDC by {@link CorrelationIdFilter} for every HTTP request so log lines
 * carry it. {@link #runWithNewId} is the equivalent scoping helper for background-job work (the
 * scan/expand pollers, see {@code ScanPoller}/{@code ExpandPoller}).
 */
public final class Correlation {

    public static final String CID = "cid";

    private Correlation() {
    }

    /**
     * Runs {@code job} with a fresh correlation id stamped into MDC for its duration, clearing MDC
     * in a {@code finally} once it's done -- mirrors {@link CorrelationIdFilter}'s
     * put-then-clear-in-finally shape for the one-correlation-id-per-HTTP-request path, but scoped
     * to a single background-job execution instead (one poller claim-and-run of one job; a retried
     * job's next attempt gets its own fresh id, not this one reused).
     * <p>
     * {@code MDC.clear()} rather than {@code MDC.remove(CID)}: poller ticks reuse a small,
     * long-lived thread (or pool), and clearing everything -- not just this key -- guarantees a
     * failed job can never leak stale MDC state into whatever runs next on that thread, even if
     * something nested here someday sets other MDC keys.
     */
    public static void runWithNewId(Runnable job) {
        MDC.put(CID, CorrelationIds.newId());
        try {
            job.run();
        } finally {
            MDC.clear();
        }
    }
}
