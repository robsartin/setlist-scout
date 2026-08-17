package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.PollerProperties;
import com.robsartin.setlistscout.shared.observability.Correlation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Paced claim-lease poller that drains {@code artist_import} (#177) -- the {@code scan.ScanPoller}
 * counterpart for queued bulk-upload names, following the same house pattern (ADR-0023): claim a
 * batch, run each, record success or failure with backoff. On by default via {@code
 * setlistscout.import-poller-enabled}. Ticks far more often than the scan/expand pollers (5s vs
 * 90s) because this work is database-only -- there's no external API to pace against, and a
 * 1,138-name import should drain in minutes, not hours.
 * <p>
 * Two deliberate differences from {@code ScanPoller}, its structural (not semantic) model:
 * <ol>
 *   <li><b>An import row is terminal on success.</b> A scan job returns to {@code SCHEDULED} and
 *   runs again on an interval; an import row becomes {@link ArtistImportStatus#DONE} and is never
 *   claimed again -- {@code ArtistImportRepository#claimDue} only ever selects {@code PENDING}
 *   rows.</li>
 *   <li><b>No per-source interval or backoff cap/park.</b> There's nothing external to pace
 *   against, so a failing row climbs the same exponential backoff ladder {@code ScanPoller} uses,
 *   uncapped by any interval -- once {@code attempts} reaches {@link #importMaxAttempts}, the row
 *   goes {@link ArtistImportStatus#FAILED} for good instead of "parking" on a longer cadence.</li>
 * </ol>
 * A third, smaller difference: unlike {@code ScanJob}/{@code ExpandJob}, {@link ArtistImport} has
 * no {@code @Version} column, and nothing else ever mutates a claimed row out from under this
 * poller -- there's no re-due and no cancel-in-flight for an import row (see the plan's "not in
 * scope" list). So there's no {@code OptimisticLockingFailureException} race to swallow here the
 * way {@code ScanPoller} does around its {@code save} calls.
 * <p>
 * {@code import-batch-size} / {@code import-max-attempts} are bound via {@code @Value} rather than
 * added to {@link PollerProperties}: that record's own class doc scopes it explicitly to {@code
 * scan_job} / {@code expand_job}, and import's "hard cap, then terminal" semantics don't share
 * {@code pollerParkCap}'s "park, keep retrying forever" meaning -- so this stays its own small
 * config surface instead of blurring into the other two pollers' knobs. The claim lease ({@code
 * setlistscout.job-lease-ms}) is the one poller setting reused as-is from {@link PollerProperties}:
 * it's already generic ("how long a claimed-but-not-completed job stays claimed"), not scan-specific.
 */
@Component
@ConditionalOnProperty(name = "setlistscout.import-poller-enabled", havingValue = "true", matchIfMissing = true)
public class ArtistImportPoller {

    private static final Logger log = LoggerFactory.getLogger(ArtistImportPoller.class);

    /** Same bound as {@code ScanPoller.LAST_ERROR_MAX_LEN} on the shared {@code text last_error} shape. */
    static final int LAST_ERROR_MAX_LEN = 8000;

    /** Same first backoff step as {@code ScanPoller.BACKOFF_BASE}; doubles per attempt, uncapped
     * (no per-source interval to cap against) until {@link #importMaxAttempts} sends the row to
     * FAILED instead of climbing further. */
    static final Duration BACKOFF_BASE = Duration.ofMinutes(10);

    private final ArtistImportRepository artistImportRepository;
    private final ArtistSeedService artistSeedService;
    private final PollerProperties properties;
    private final int importBatchSize;
    private final int importMaxAttempts;
    private final Clock clock;

    // Explicit @Autowired: there are two constructors here (this one plus the Clock test seam
    // below), so Spring's implicit single-constructor autowiring doesn't apply -- without this,
    // Spring falls back to a non-existent no-arg constructor and context startup fails. Same
    // pitfall ScanPoller's constructor guards against.
    @Autowired
    public ArtistImportPoller(ArtistImportRepository artistImportRepository, ArtistSeedService artistSeedService,
                               PollerProperties properties,
                               @Value("${setlistscout.import-batch-size:25}") int importBatchSize,
                               @Value("${setlistscout.import-max-attempts:3}") int importMaxAttempts) {
        this(artistImportRepository, artistSeedService, properties, importBatchSize, importMaxAttempts,
                Clock.systemUTC());
    }

    /** Test seam: a fixed/controllable clock so backoff-time assertions aren't racy. */
    ArtistImportPoller(ArtistImportRepository artistImportRepository, ArtistSeedService artistSeedService,
                        PollerProperties properties, int importBatchSize, int importMaxAttempts, Clock clock) {
        this.artistImportRepository = artistImportRepository;
        this.artistSeedService = artistSeedService;
        this.properties = properties;
        this.importBatchSize = importBatchSize;
        this.importMaxAttempts = importMaxAttempts;
        this.clock = clock;
    }

    /**
     * {@code initialDelayString} deliberately reuses {@code import-tick-ms} rather than a separate
     * initial-delay key -- same rationale as {@code ScanPoller}.
     */
    @Scheduled(fixedDelayString = "${setlistscout.import-tick-ms:5000}",
               initialDelayString = "${setlistscout.import-tick-ms:5000}")
    public void tick() {
        Instant now = clock.instant();
        Instant leaseCutoff = now.minus(Duration.ofMillis(properties.jobLeaseMs()));
        List<ArtistImport> claimed = artistImportRepository.claimDue(now, leaseCutoff, importBatchSize);
        for (ArtistImport row : claimed) {
            runOne(row, now);
        }
    }

    private void runOne(ArtistImport row, Instant now) {
        try {
            // One fresh correlation id per row, mirroring ScanPoller/ExpandPoller (#135) so any
            // logging the seed call and its ArtistActivated cascade triggers threads together.
            Correlation.runWithNewId(() -> artistSeedService.addSeedIfNew(row.getOwner(), row.getName()));
            recordSuccess(row);
        } catch (RuntimeException ex) {
            recordFailure(row, now, ex);
        }
    }

    /**
     * Terminal on success -- unlike a scan job, this row never runs again. {@code addSeedIfNew}
     * returning {@code false} (the name already matched an active artist) reaches this same path:
     * it is a legitimate no-op, not a failure, and marking it FAILED would make a normal import
     * (most names in a re-uploaded or overlapping list) look broken.
     */
    private void recordSuccess(ArtistImport row) {
        row.setStatus(ArtistImportStatus.DONE);
        row.setClaimedAt(null);
        artistImportRepository.save(row);
    }

    private void recordFailure(ArtistImport row, Instant now, RuntimeException ex) {
        int attempts = row.getAttempts() + 1;

        log.atWarn().addKeyValue("owner", row.getOwner()).addKeyValue("name", row.getName())
                .addKeyValue("attempts", attempts).setCause(ex).log("artist import failed");

        row.setAttempts(attempts);
        row.setLastError(truncate(ex.getMessage()));
        row.setClaimedAt(null);
        if (attempts >= importMaxAttempts) {
            // Terminal: FAILED rows are never selected by claimDue again, unlike ScanPoller's
            // "park" (still FAILED, but still eventually reclaimed on a long cadence).
            row.setStatus(ArtistImportStatus.FAILED);
        } else {
            row.setStatus(ArtistImportStatus.PENDING);
            row.setNextDueAt(now.plus(nextDelay(attempts)));
        }
        artistImportRepository.save(row);
    }

    /** Exponential backoff (10m * 2^attempts), uncapped -- there's no per-source interval to cap
     * against here; {@link #importMaxAttempts} stops the climb by going terminal instead. */
    private Duration nextDelay(int attempts) {
        int shift = Math.min(attempts, 30);   // cap the shift; >2^30 * 10m is already absurd
        return BACKOFF_BASE.multipliedBy(1L << shift);
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= LAST_ERROR_MAX_LEN ? message : message.substring(0, LAST_ERROR_MAX_LEN);
    }
}
