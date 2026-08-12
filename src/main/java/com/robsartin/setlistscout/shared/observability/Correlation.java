package com.robsartin.setlistscout.shared.observability;

import org.slf4j.MDC;

/**
 * Sets correlation fields into SLF4J's MDC so every log line inside a unit of work carries them.
 * {@link #run} is used for background work (scheduled and manual async jobs); the HTTP path is
 * handled by {@link CorrelationIdFilter}.
 */
public final class Correlation {

    public static final String CID = "cid";
    public static final String OWNER = "owner";
    public static final String JOB = "job";
    public static final String PARENT_CID = "parentCid";

    private Correlation() {
    }

    /**
     * Run {@code body} with a fresh {@code cid} plus {@code job} (and {@code owner}/{@code parentCid}
     * when non-null) in MDC, clearing all four afterward even if {@code body} throws.
     */
    public static void run(String job, String owner, String parentCid, Runnable body) {
        MDC.put(CID, CorrelationIds.newId());
        MDC.put(JOB, job);
        if (owner != null) {
            MDC.put(OWNER, owner);
        }
        if (parentCid != null) {
            MDC.put(PARENT_CID, parentCid);
        }
        try {
            body.run();
        } finally {
            MDC.remove(CID);
            MDC.remove(JOB);
            MDC.remove(OWNER);
            MDC.remove(PARENT_CID);
        }
    }
}
