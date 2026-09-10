package com.robsartin.setlistscout.scan;

import java.time.Instant;

/**
 * One show source's state for the admin queues page (issue #273).
 *
 * <p>#265 surfaces source health as a banner on the Shows page, which is an <em>alarm</em>: it
 * appears only once a source is already dead. This is the <em>status board</em> -- every source,
 * healthy ones included, so "is Bandsintown working right now?" is answerable when the answer is
 * yes. After the 2026-08-25 outage the only way to confirm recovery was to read the logs, which is
 * the same place the failure hid for six hours.
 *
 * <p>A plain record assembled by {@code SourceHealthService#allSourceStatus} -- no entity, no
 * Spring, so the admin module can hand it straight to the view.
 *
 * @param enabled whether a {@code ShowSource} bean for this id exists in the running application.
 *                {@code setlistscout.sources.<id>=false} (#139) drops the bean entirely, and nothing
 *                in the UI said so before this.
 */
public record SourceStatusRow(
        String source,
        boolean enabled,
        boolean healthy,
        Instant lastSuccessAt,
        Instant lastFailureAt,
        int consecutiveFailures,
        String streakSignature,
        Instant unhealthySince,
        String lastError
) {

    /**
     * @return {@code "Off"}, {@code "Down"} or {@code "On"} -- checked in that order.
     *
     * <p>{@code enabled} is tested FIRST on purpose. A disabled source can hold a stale unhealthy
     * row forever, because with no bean it can record neither a failure nor a success (see
     * {@code SourceHealthService#unhealthyDetail}). Reporting that as "Down" would describe a source
     * that is not even being called, and would make the one genuinely alarming state less credible.
     */
    public String state() {
        if (!enabled) {
            return "Off";
        }
        return healthy ? "On" : "Down";
    }

    /** @return true when there is a failure streak worth showing beside an otherwise-On source. */
    public boolean hasStreak() {
        return consecutiveFailures > 0;
    }
}
