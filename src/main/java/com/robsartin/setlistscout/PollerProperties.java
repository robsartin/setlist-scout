package com.robsartin.setlistscout;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.Map;

/**
 * Config for the paced claim-lease pollers (Phase B PR4a) that drain {@code scan_job} /
 * {@code expand_job}: {@code scan.ScanPoller} and {@code expansion.ExpandPoller}. A separate
 * {@code @ConfigurationProperties} bean from {@link AppProperties} (both bind the same
 * {@code setlistscout} prefix; Spring Boot allows this as long as the field names don't
 * collide, and they don't here) so this stays a clean home for poller-only knobs rather than
 * bloating the API-keys-and-startup-defaults one.
 * <p>
 * The pollers themselves are gated off by default via
 * {@code setlistscout.scan-poller-enabled} / {@code expand-poller-enabled}
 * ({@code @ConditionalOnProperty} on the poller beans, not represented here since a false/absent
 * flag means the bean -- and therefore this record's values -- are never consulted). The tick
 * cadence ({@code setlistscout.scan-tick-ms} / {@code expand-tick-ms}) isn't here either: it's
 * only ever read once, directly in each poller's {@code @Scheduled} SpEL default.
 */
@ConfigurationProperties(prefix = "setlistscout")
public record PollerProperties(
        int scanBatchSize,
        int expandBatchSize,
        long jobLeaseMs,
        Duration scanInterval,
        Duration expansionInterval,
        int pollerParkCap,
        Map<String, Duration> sourceIntervals,
        boolean jobBackfillEnabled,
        Duration backfillSpread
) {
    public PollerProperties {
        if (sourceIntervals == null) {
            sourceIntervals = Map.of();
        }
    }

    /**
     * The scheduling interval for one source: an explicit
     * {@code setlistscout.source-intervals.<sourceId>} override if present, else
     * {@code fallback} (the poller's own {@link #scanInterval} or {@link #expansionInterval}).
     */
    public Duration intervalFor(String sourceId, Duration fallback) {
        return sourceIntervals.getOrDefault(sourceId, fallback);
    }
}
