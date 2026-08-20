package com.robsartin.setlistscout.scan;

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
 * Paced claim-lease poller that drains {@code venue_scan_job} (#206 Task 3) -- copies {@code
 * catalog.ArtistImportPoller}'s shape: claim a due batch, run each claimed row, one fresh
 * correlation id per row. On by default via {@code setlistscout.venue-poller-enabled}, matching
 * {@code ArtistImportPoller}'s "on by default" choice rather than {@code ScanPoller}/{@code
 * ExpandPoller}'s off-by-default one -- there is no equivalent of those two's #172 boot-time flood
 * risk here (nothing seeds {@code venue_scan_job} at startup).
 * <p>
 * <b>Unlike {@code ArtistImportPoller}/{@code ScanPoller}, this poller does not itself record
 * success or failure.</b> That responsibility belongs entirely to {@link VenueScanRunner#run},
 * which never throws under normal operation (a scrape or persistence failure is caught and
 * recorded on the job internally) -- see its own javadoc. {@link #runOne} still wraps the call in
 * a try/catch purely as defense in depth: if something genuinely unanticipated ever escaped the
 * runner anyway, one bad row must not stall the rest of this tick's claimed batch.
 * <p>
 * {@code venue-batch-size} / {@code venue-scan-interval} are bound via {@code @Value} rather than
 * added to {@link PollerProperties}, for the same reason {@code ArtistImportPoller} does the same
 * for its own batch/attempts knobs: that record's class doc scopes it explicitly to {@code
 * scan_job} / {@code expand_job}. The claim lease ({@code setlistscout.job-lease-ms}) is reused
 * as-is from {@link PollerProperties}, also matching {@code ArtistImportPoller}: it's already
 * generic ("how long a claimed-but-not-completed job stays claimed"), not scan-specific.
 */
@Component
@ConditionalOnProperty(name = "setlistscout.venue-poller-enabled", havingValue = "true", matchIfMissing = true)
public class VenueScanPoller {

    private static final Logger log = LoggerFactory.getLogger(VenueScanPoller.class);

    private final VenueScanJobRepository venueScanJobRepository;
    private final VenueScanRunner venueScanRunner;
    private final PollerProperties properties;
    private final int venueBatchSize;
    private final Clock clock;

    // Explicit @Autowired: there are two constructors here (this one plus the Clock test seam
    // below), so Spring's implicit single-constructor autowiring doesn't apply -- without this,
    // Spring falls back to a non-existent no-arg constructor and context startup fails. Same
    // pitfall ArtistImportPoller/ScanPoller's constructors guard against.
    @Autowired
    public VenueScanPoller(VenueScanJobRepository venueScanJobRepository,
                            VenueScanRunner venueScanRunner,
                            PollerProperties properties,
                            @Value("${setlistscout.venue-batch-size:25}") int venueBatchSize) {
        this(venueScanJobRepository, venueScanRunner, properties, venueBatchSize, Clock.systemUTC());
    }

    /** Test seam: a fixed/controllable clock so claim-window assertions aren't racy. */
    VenueScanPoller(VenueScanJobRepository venueScanJobRepository, VenueScanRunner venueScanRunner,
                     PollerProperties properties, int venueBatchSize, Clock clock) {
        this.venueScanJobRepository = venueScanJobRepository;
        this.venueScanRunner = venueScanRunner;
        this.properties = properties;
        this.venueBatchSize = venueBatchSize;
        this.clock = clock;
    }

    /**
     * {@code initialDelayString} deliberately reuses {@code venue-tick-ms} rather than a separate
     * initial-delay key -- same rationale as {@code ArtistImportPoller}/{@code ScanPoller}.
     */
    @Scheduled(fixedDelayString = "${setlistscout.venue-tick-ms:5000}",
               initialDelayString = "${setlistscout.venue-tick-ms:5000}")
    public void tick() {
        Instant now = clock.instant();
        Instant leaseCutoff = now.minus(Duration.ofMillis(properties.jobLeaseMs()));
        List<VenueScanJob> claimed = venueScanJobRepository.claimDue(now, leaseCutoff, venueBatchSize);
        for (VenueScanJob job : claimed) {
            runOne(job);
        }
    }

    private void runOne(VenueScanJob job) {
        try {
            // One fresh correlation id per row, mirroring ScanPoller/ArtistImportPoller (#135) so
            // any logging the scrape and persistence triggers threads together.
            Correlation.runWithNewId(() -> venueScanRunner.run(job));
        } catch (RuntimeException ex) {
            // Defense in depth only -- VenueScanRunner#run is documented to never throw (see its
            // javadoc). If something unanticipated escapes it anyway, one bad row must not stall
            // the rest of this tick's claimed batch; the next tick picks this job back up.
            log.atWarn().addKeyValue("owner", job.getOwner()).addKeyValue("venueId", job.getVenueId())
                    .setCause(ex).log("unexpected exception escaped VenueScanRunner.run");
        }
    }
}
