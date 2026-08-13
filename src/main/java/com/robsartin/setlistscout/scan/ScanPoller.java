package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.PollerProperties;
import com.robsartin.setlistscout.shared.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Paced claim-lease poller for {@code scan_job} (Phase B PR4a/PR4b) -- on by default via
 * {@code setlistscout.scan-poller-enabled}, the sole driver of scans now that the old whole-fleet
 * batch scheduler is retired. Setting the flag to {@code false} means this bean is never created
 * (e.g. for a test slice that doesn't want a live poller running). Draining {@code SCHEDULED}
 * rows happens a {@link PollerProperties#scanBatchSize batch} at a time.
 * <p>
 * Each tick claims due rows ({@code ScanJobRepository#claimDue}, an atomic
 * {@code FOR UPDATE SKIP LOCKED}), runs {@link ScanUnitRunner} for each one, and reschedules or
 * backs it off. The claimed rows are run <em>outside</em> any transaction -- the adapter calls
 * inside {@code ScanUnitRunner.run} can be slow (HTTP calls to Ticketmaster/Bandsintown/a band
 * site) and holding a DB transaction open across that would tie up a connection for no reason.
 * The bookkeeping update after each unit run goes through {@code scanJobRepository.save(job)}
 * directly rather than a {@code @Transactional} method on this class: {@code SimpleJpaRepository}
 * (an external bean, not {@code this}) is itself {@code @Transactional}, so each {@code save}
 * already gets its own short transaction for free. Wrapping a same-class method in
 * {@code @Transactional} here instead would be a no-op -- Spring's proxy-based AOP only
 * intercepts external calls, not this-calls-this within the same bean -- so this sidesteps that
 * trap entirely rather than working around it.
 */
@Component
@ConditionalOnProperty(name = "setlistscout.scan-poller-enabled", havingValue = "true")
public class ScanPoller {

    private static final Logger log = LoggerFactory.getLogger(ScanPoller.class);

    /** Cap on {@code varchar(255) last_error} (scan_job / expand_job schema, V6/V7). */
    static final int LAST_ERROR_MAX_LEN = 255;

    /** First backoff step on failure; doubles per attempt up to the source's own interval. */
    static final Duration BACKOFF_BASE = Duration.ofMinutes(10);

    private final ScanJobRepository scanJobRepository;
    private final ScanUnitRunner scanUnitRunner;
    private final PollerProperties properties;
    private final Clock clock;

    // Explicit @Autowired: there are two constructors here (this one plus the Clock test seam
    // below), so Spring's implicit single-constructor autowiring doesn't apply -- without this,
    // Spring falls back to a non-existent no-arg constructor and context startup fails. Same
    // pitfall ServiceBeanWiringTest guards against for the RestClient-backed services.
    @Autowired
    public ScanPoller(ScanJobRepository scanJobRepository, ScanUnitRunner scanUnitRunner,
                       PollerProperties properties) {
        this(scanJobRepository, scanUnitRunner, properties, Clock.systemUTC());
    }

    /** Test seam: a fixed/controllable clock so reschedule-time assertions aren't racy. */
    ScanPoller(ScanJobRepository scanJobRepository, ScanUnitRunner scanUnitRunner,
               PollerProperties properties, Clock clock) {
        this.scanJobRepository = scanJobRepository;
        this.scanUnitRunner = scanUnitRunner;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * {@code initialDelayString} deliberately reuses {@code scan-tick-ms} rather than a separate
     * initial-delay key -- waiting one tick before the first run is a perfectly good default, and
     * it avoids a second property just for this.
     */
    @Scheduled(fixedDelayString = "${setlistscout.scan-tick-ms:90000}",
               initialDelayString = "${setlistscout.scan-tick-ms:90000}")
    public void tick() {
        Instant now = clock.instant();
        Instant leaseCutoff = now.minus(Duration.ofMillis(properties.jobLeaseMs()));
        List<ScanJob> claimed = scanJobRepository.claimDue(now, leaseCutoff, properties.scanBatchSize());
        for (ScanJob job : claimed) {
            runOne(job, now);
        }
    }

    private void runOne(ScanJob job, Instant now) {
        try {
            scanUnitRunner.run(job.getOwner(), job.getArtistId(), job.getSource());
            recordSuccess(job, now);
        } catch (OptimisticLockingFailureException concurrentChange) {
            // The job was re-dued (SettingsChanged / manual "Scan now") or deleted (ArtistDeactivated)
            // while we ran it. That writer's intent wins -- drop our stale reschedule and move on.
            log.atInfo().addKeyValue("owner", job.getOwner()).addKeyValue("artistId", job.getArtistId())
                    .addKeyValue("source", job.getSource())
                    .log("scan job changed concurrently during run; skipping reschedule");
        } catch (RuntimeException ex) {
            try {
                recordFailure(job, now, ex);
            } catch (OptimisticLockingFailureException concurrentChange) {
                // Same race as above, but hit on the failure-reschedule save instead of the
                // success-reschedule save: someone re-dued or deleted this job while the unit
                // ran (and failed). Their write wins -- drop our stale failure bookkeeping.
                log.atInfo().addKeyValue("owner", job.getOwner()).addKeyValue("artistId", job.getArtistId())
                        .addKeyValue("source", job.getSource())
                        .log("scan job changed concurrently during failure reschedule; skipping");
            }
        }
    }

    private void recordSuccess(ScanJob job, Instant now) {
        job.setLastRunAt(now);
        job.setNextDueAt(now.plus(interval(job.getSource())));
        job.setAttempts(0);
        job.setClaimedAt(null);
        job.setStatus(JobStatus.SCHEDULED);
        scanJobRepository.save(job);
    }

    private void recordFailure(ScanJob job, Instant now, RuntimeException ex) {
        int attempts = job.getAttempts() + 1;
        Duration interval = interval(job.getSource());

        log.atWarn().addKeyValue("owner", job.getOwner()).addKeyValue("artistId", job.getArtistId())
                .addKeyValue("source", job.getSource()).addKeyValue("attempts", attempts)
                .setCause(ex).log("scan unit failed");

        job.setAttempts(attempts);
        job.setLastError(truncate(ex.getMessage()));
        job.setClaimedAt(null);
        job.setStatus(JobStatus.FAILED);
        job.setNextDueAt(now.plus(nextDelay(attempts, interval)));
        scanJobRepository.save(job);
    }

    /**
     * Explicit {@code setlistscout.source-intervals.<sourceId>} override, else
     * {@link PollerProperties#scanInterval()} (14d default).
     */
    private Duration interval(String sourceId) {
        return properties.intervalFor(sourceId, properties.scanInterval());
    }

    /**
     * Exponential backoff (10m * 2^attempts) capped at the source's own interval -- once the
     * backoff would exceed the normal cadence there's no point waiting longer than a normal
     * re-check. Once {@code attempts} reaches {@link PollerProperties#pollerParkCap()} (default
     * 6), the job is <em>parked</em>: instead of continuing to climb the backoff ladder every
     * ~90s-scale tick, it's pushed a full {@code interval} out. It stays {@code FAILED} with
     * {@code lastError} intact for a human to see, and will still be picked up again (this isn't
     * a terminal/dead state) -- just not on a near-term retry cadence that would otherwise hammer
     * a consistently-broken source.
     */
    private Duration nextDelay(int attempts, Duration interval) {
        if (attempts >= properties.pollerParkCap()) {
            return interval;
        }
        int shift = Math.min(attempts, 30);   // cap the shift; >2^30 * 10m already dwarfs any interval
        Duration exponential = BACKOFF_BASE.multipliedBy(1L << shift);
        return exponential.compareTo(interval) < 0 ? exponential : interval;
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= LAST_ERROR_MAX_LEN ? message : message.substring(0, LAST_ERROR_MAX_LEN);
    }
}
