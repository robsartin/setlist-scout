package com.robsartin.setlistscout.shared.observability;

/**
 * Correlation field name(s) shared across the request-scoped correlation path. {@link #CID} is
 * stamped into SLF4J's MDC by {@link CorrelationIdFilter} for every HTTP request so log lines
 * carry it.
 */
public final class Correlation {

    public static final String CID = "cid";

    private Correlation() {
    }
}
