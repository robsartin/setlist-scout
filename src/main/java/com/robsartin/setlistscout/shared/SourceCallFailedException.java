package com.robsartin.setlistscout.shared;

/**
 * A show source's call to its provider genuinely FAILED, as distinct from succeeding and finding
 * nothing (issue #265).
 *
 * <p>Those two were the same value until now. {@code BandsintownService} caught {@code Exception},
 * logged a WARN and returned {@code List.of()}, so {@code ScanUnitRunner} saw an empty list and
 * {@code ScanPoller} recorded a success -- which is how <b>2,857 consecutive 403s were written to
 * the database as 2,857 successful scans</b> over six hours, each one re-duing its job a full 14-day
 * interval out. Nothing surfaced it because, from the poller's side, nothing had gone wrong.
 *
 * <h2>Not a replacement for {@link TransientSourceException}</h2>
 * The two answer different questions and both are needed:
 * <ul>
 *   <li>{@link TransientSourceException} (#263) means <em>retry this artist</em>: a 429 or 5xx, where
 *       the same call later may well work. It propagates out of {@code ScanUnitRunner} and the job
 *       goes on the poller's backoff ladder.</li>
 *   <li>This one means <em>the call did not succeed</em>, full stop. It is caught inside
 *       {@code ScanUnitRunner}, recorded against the source's health, and then deliberately
 *       swallowed into an empty result -- because #263 was right that retrying a name the provider
 *       cannot parse changes nothing, and failing those jobs would fill the ladder with permanent
 *       failures.</li>
 * </ul>
 * A {@link TransientSourceException} is therefore ALSO a call failure and is recorded as one; it
 * simply additionally fails the job. What #265 adds is that the non-transient case stops being
 * invisible.
 *
 * <h2>The signature</h2>
 * {@link #signature()} is the coarse "what went wrong" key -- an HTTP status code, or a class name
 * for an I/O failure. A dead source fails with the SAME signature every time (Bandsintown: {@code
 * 403}, over and over); a source that is merely having a bad day fails with a mix. Source health
 * requires one unbroken signature, so the two do not look alike.
 *
 * <p>Unchecked so a source adapter need not widen its signature, matching
 * {@link TransientSourceException}.
 */
public class SourceCallFailedException extends RuntimeException {

    private final String signature;

    public SourceCallFailedException(String signature, String message, Throwable cause) {
        super(message, cause);
        this.signature = signature;
    }

    /** Coarse failure key: an HTTP status code ({@code "403"}), or an exception class name. */
    public String signature() {
        return signature;
    }
}
