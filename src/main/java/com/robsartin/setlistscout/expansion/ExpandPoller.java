package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.PollerProperties;
import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.observability.Correlation;
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
import java.util.Optional;

/**
 * Paced claim-lease poller for {@code expand_job} (Phase B PR4a) -- the {@code scan.ScanPoller}
 * counterpart for expansion. Gated off by default via
 * {@code setlistscout.expand-poller-enabled}; see {@code ScanPoller}'s class doc for the shared
 * rationale (claim-then-run-then-reschedule shape, no transaction held across the slow adapter
 * call, why {@code save} rather than a same-class {@code @Transactional} method, and why
 * {@code initialDelayString} reuses the tick-ms key rather than a separate initial-delay one).
 * <p>
 * {@link ExpandUnitRunner#run} needs the base artist's <em>name</em>, but the job only carries
 * {@code (owner, artistId, source)}, so each claimed job resolves the name via
 * {@code ArtistRepository.findByIdAndOwner} first. A missing artist (deleted/deactivated between
 * enqueue and this tick, racing {@code ArtistDeactivated}'s job cleanup) is treated the same as a
 * successful no-op run -- consistent with {@code ScanUnitRunner}'s own missing-artist handling --
 * rather than a failure: it isn't a broken source, so it shouldn't count against the source's
 * backoff/park state.
 */
@Component
@ConditionalOnProperty(name = "setlistscout.expand-poller-enabled", havingValue = "true")
public class ExpandPoller {

    private static final Logger log = LoggerFactory.getLogger(ExpandPoller.class);

    /**
     * Generous truncation bound on the {@code text last_error} column (V9 widened it from
     * {@code varchar(255)}) -- long enough to keep real API/LLM failure detail intact while still
     * bounding row size.
     */
    static final int LAST_ERROR_MAX_LEN = 8000;
    static final Duration BACKOFF_BASE = Duration.ofMinutes(10);

    private final ExpandJobRepository expandJobRepository;
    private final ExpandUnitRunner expandUnitRunner;
    private final ArtistRepository artistRepository;
    private final PollerProperties properties;
    private final Clock clock;

    // Explicit @Autowired: see ScanPoller's constructor doc -- same two-constructor-without-
    // @Autowired pitfall, same fix.
    @Autowired
    public ExpandPoller(ExpandJobRepository expandJobRepository, ExpandUnitRunner expandUnitRunner,
                         ArtistRepository artistRepository, PollerProperties properties) {
        this(expandJobRepository, expandUnitRunner, artistRepository, properties, Clock.systemUTC());
    }

    /** Test seam: a fixed/controllable clock so reschedule-time assertions aren't racy. */
    ExpandPoller(ExpandJobRepository expandJobRepository, ExpandUnitRunner expandUnitRunner,
                 ArtistRepository artistRepository, PollerProperties properties, Clock clock) {
        this.expandJobRepository = expandJobRepository;
        this.expandUnitRunner = expandUnitRunner;
        this.artistRepository = artistRepository;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${setlistscout.expand-tick-ms:90000}",
               initialDelayString = "${setlistscout.expand-tick-ms:90000}")
    public void tick() {
        Instant now = clock.instant();
        Instant leaseCutoff = now.minus(Duration.ofMillis(properties.jobLeaseMs()));
        List<ExpandJob> claimed = expandJobRepository.claimDue(now, leaseCutoff, properties.expandBatchSize());
        for (ExpandJob job : claimed) {
            runOne(job, now);
        }
    }

    private void runOne(ExpandJob job, Instant now) {
        try {
            Optional<Artist> artist = artistRepository.findByIdAndOwner(job.getArtistId(), job.getOwner());
            if (artist.isPresent()) {
                // #135: one fresh correlation id per job execution, scoped to just this call so
                // every external-service log line the unit run triggers can be grepped as one
                // thread through the log stream. Deliberately NOT wrapping recordSuccess/
                // recordFailure below -- see Correlation#runWithNewId.
                Correlation.runWithNewId(() -> expandUnitRunner.run(
                        job.getOwner(), job.getArtistId(), job.getSource(), artist.get().getName()));
            } else {
                log.atWarn().addKeyValue("owner", job.getOwner()).addKeyValue("artistId", job.getArtistId())
                        .log("expand unit skipped -- artist not found");
            }
            recordSuccess(job, now);
        } catch (OptimisticLockingFailureException concurrentChange) {
            // The job was re-dued (SettingsChanged / manual "Scan now") or deleted (ArtistDeactivated)
            // while we ran it. That writer's intent wins -- drop our stale reschedule and move on.
            log.atInfo().addKeyValue("owner", job.getOwner()).addKeyValue("artistId", job.getArtistId())
                    .addKeyValue("source", job.getSource())
                    .log("expand job changed concurrently during run; skipping reschedule");
        } catch (RuntimeException ex) {
            try {
                recordFailure(job, now, ex);
            } catch (OptimisticLockingFailureException concurrentChange) {
                // Same race as above, but hit on the failure-reschedule save instead of the
                // success-reschedule save: someone re-dued or deleted this job while the unit
                // ran (and failed). Their write wins -- drop our stale failure bookkeeping.
                log.atInfo().addKeyValue("owner", job.getOwner()).addKeyValue("artistId", job.getArtistId())
                        .addKeyValue("source", job.getSource())
                        .log("expand job changed concurrently during failure reschedule; skipping");
            }
        }
    }

    private void recordSuccess(ExpandJob job, Instant now) {
        job.setLastRunAt(now);
        job.setNextDueAt(now.plus(interval(job.getSource())));
        job.setAttempts(0);
        job.setClaimedAt(null);
        job.setStatus(JobStatus.SCHEDULED);
        expandJobRepository.save(job);
    }

    private void recordFailure(ExpandJob job, Instant now, RuntimeException ex) {
        int attempts = job.getAttempts() + 1;
        Duration interval = interval(job.getSource());

        log.atWarn().addKeyValue("owner", job.getOwner()).addKeyValue("artistId", job.getArtistId())
                .addKeyValue("source", job.getSource()).addKeyValue("attempts", attempts)
                .setCause(ex).log("expand unit failed");

        job.setAttempts(attempts);
        job.setLastError(truncate(ex.getMessage()));
        job.setClaimedAt(null);
        job.setStatus(JobStatus.FAILED);
        job.setNextDueAt(now.plus(nextDelay(attempts, interval)));
        expandJobRepository.save(job);
    }

    /**
     * Explicit {@code setlistscout.source-intervals.<sourceId>} override, else
     * {@link PollerProperties#expansionInterval()} (28d default).
     */
    private Duration interval(String sourceId) {
        return properties.intervalFor(sourceId, properties.expansionInterval());
    }

    /** See {@code ScanPoller#nextDelay} for the backoff/park rationale -- identical here. */
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
