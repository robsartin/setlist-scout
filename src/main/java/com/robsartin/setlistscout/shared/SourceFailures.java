package com.robsartin.setlistscout.shared;

import org.springframework.web.client.HttpStatusCodeException;

/**
 * The one place that decides what a failed provider call MEANS (issues #263, #265).
 *
 * <p>Extracted so {@code TicketmasterService} and {@code BandsintownService} cannot drift apart on
 * it -- and they had. #263 taught Ticketmaster to rethrow 429/5xx as {@link
 * TransientSourceException}, but Bandsintown was never given the same treatment: it caught bare
 * {@code Exception} and returned an empty list for everything, so it had no retry ladder for a rate
 * limit AND no way to report a dead credential. Both gaps were invisible for the same reason.
 */
public final class SourceFailures {

    private SourceFailures() {
    }

    /**
     * @return the exception a source adapter should throw for {@code cause}: a
     * {@link TransientSourceException} when retrying the same call could plausibly work (429, 5xx),
     * otherwise a {@link SourceCallFailedException} carrying {@link #signatureOf}.
     *
     * <p>The transient set stays exactly #263's, deliberately: a 4xx from a name the provider cannot
     * parse would fail identically on every retry, so it must not go on the backoff ladder. What
     * #265 changes is only that it is no longer <em>silent</em> -- it is still a quiet empty result
     * for that one artist, but it now counts against the source's health.
     */
    public static RuntimeException classify(String sourceId, String artistName, Exception cause) {
        String where = sourceId + " call failed for " + artistName;
        if (cause instanceof HttpStatusCodeException status) {
            int code = status.getStatusCode().value();
            if (code == 429 || code >= 500) {
                return new TransientSourceException(where + " (" + code + ")", cause);
            }
        }
        return new SourceCallFailedException(signatureOf(cause), where, cause);
    }

    /**
     * @return the coarse "what went wrong" key: the HTTP status code as a string, else the
     * exception's simple class name. A dead source repeats ONE signature; a source merely having a
     * bad day produces a mix, which is what stops {@code SourceHealth} confusing the two.
     */
    public static String signatureOf(Throwable cause) {
        if (cause instanceof HttpStatusCodeException status) {
            return String.valueOf(status.getStatusCode().value());
        }
        return cause == null ? "unknown" : cause.getClass().getSimpleName();
    }
}
