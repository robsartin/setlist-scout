package com.robsartin.setlistscout.shared;

/**
 * A show/relation source failed in a way that is worth retrying -- rate-limited, or a server-side
 * error at the provider.
 *
 * <p>Exists so a transient failure can be told apart from "this artist genuinely has no shows"
 * (#263). Before it, {@code TicketmasterService} swallowed every failure into an empty list, so
 * {@code ScanPoller} called {@code recordSuccess} and pushed the job a full interval (14 days) out.
 * A rate-limited scan therefore looked exactly like a successful empty one, and {@code scan_job}
 * recorded zero failures for a source the logs showed failing ~5% of the time.
 *
 * <p>Unchecked on purpose: {@code ScanPoller.runOne} already catches {@link RuntimeException} and
 * routes it to {@code recordFailure}, which puts the job on the backoff ladder. Nothing else needs
 * to change for this to be handled correctly.
 */
public class TransientSourceException extends RuntimeException {

    public TransientSourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
