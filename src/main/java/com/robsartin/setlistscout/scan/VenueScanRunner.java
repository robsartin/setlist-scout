package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.JobStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Scans one followed venue's calendar (#206 Task 3) and persists whatever shows come back, then
 * records the outcome on the {@link VenueScanJob} itself. Unlike {@code scan.ScanPoller} /
 * {@code catalog.ArtistImportPoller}, which own success/failure bookkeeping themselves in the
 * poller, that responsibility lives here: {@link #run}'s signature takes only the job, with no
 * {@code Instant now} passed in from a poller batch tick, and the plan's own failure test drives
 * this method directly and asserts the job's post-run state.
 * <p>
 * <b>No radius filter</b> (unlike {@code scan.source.BandSiteShowSource}): the owner chose this
 * venue deliberately, and a venue does not move. That's the spec's stated decision (#206), not an
 * oversight -- do not "fix" it by adding one back.
 * <p>
 * <b>{@code status} is deliberately left untouched on failure.</b> {@link VenueScanJobRepository
 * #claimDue} (#206 Task 2, already committed) only ever selects rows with {@code status =
 * 'SCHEDULED'}, and its claim {@code UPDATE} sets only {@code claimed_at} -- never {@code status}
 * itself -- unlike {@code scan_job}'s claim, which flips to RUNNING. So a venue job's status stays
 * SCHEDULED for its entire claimed-and-running lifetime by construction. Setting it to FAILED on a
 * failed run, the way {@code ScanPoller.recordFailure} does for {@code scan_job}, would make that
 * row permanently unclaimable by {@code claimDue}'s own hard {@code status = 'SCHEDULED'} filter --
 * a silently dead job, never retried again. Leaving {@code status} alone on failure keeps it
 * exactly where {@code claimDue} needs it to be reclaimed at its backed-off {@code next_due_at}.
 */
@Service
public class VenueScanRunner {

    private static final Logger log = LoggerFactory.getLogger(VenueScanRunner.class);

    /** Same bound as ScanPoller.LAST_ERROR_MAX_LEN / ArtistImportPoller.LAST_ERROR_MAX_LEN on the shared {@code text last_error} shape. */
    static final int LAST_ERROR_MAX_LEN = 8000;

    /** Same first backoff step as ScanPoller.BACKOFF_BASE; doubles per attempt, capped at {@link #scanInterval} (mirrors ScanPoller.nextDelay). */
    static final Duration BACKOFF_BASE = Duration.ofMinutes(10);

    private final VenueRepository venueRepository;
    private final VenueScanJobRepository venueScanJobRepository;
    private final ShowRepository showRepository;
    private final SettingsService settingsService;
    private final BandSiteScraperService scraper;
    private final Duration scanInterval;

    public VenueScanRunner(VenueRepository venueRepository,
                            VenueScanJobRepository venueScanJobRepository,
                            ShowRepository showRepository,
                            SettingsService settingsService,
                            BandSiteScraperService scraper,
                            @Value("${setlistscout.venue-scan-interval:14d}") Duration scanInterval) {
        this.venueRepository = venueRepository;
        this.venueScanJobRepository = venueScanJobRepository;
        this.showRepository = showRepository;
        this.settingsService = settingsService;
        this.scraper = scraper;
        this.scanInterval = scanInterval;
    }

    /**
     * Scans {@code job}'s venue and persists whatever shows come back, then records success or
     * failure on {@code job} itself. Never throws -- a missing venue, a scraper failure, or a
     * persistence failure is caught, recorded as {@code lastError} with backoff, and the job's
     * claim is released either way.
     */
    public void run(VenueScanJob job) {
        try {
            Venue venue = venueRepository.findByIdAndOwner(job.getVenueId(), job.getOwner())
                    .orElseThrow(() -> new IllegalStateException(
                            "venue missing for venue_scan_job id=" + job.getId() + " venueId=" + job.getVenueId()));

            SearchSettings settings = settingsService.getOrCreateSettings(job.getOwner());
            LocalDateTime start = LocalDateTime.now();
            LocalDateTime end = start.plusMonths(settings.getMonthsAhead());

            // scrapeShows already carries each show's own performer name (falling back to the
            // venue name passed in) and MUSIC/COMEDY kind (#208, PR #216) -- nothing to re-derive
            // here, just persist what it returns.
            List<Show> shows = scraper.scrapeShows(venue.getName(), venue.getCalendarUrl(), start, end);
            int saved = persist(job.getOwner(), venue.getCalendarUrl(), shows);

            log.atDebug().addKeyValue("owner", job.getOwner()).addKeyValue("venueId", venue.getId())
                    .addKeyValue("found", shows.size()).addKeyValue("saved", saved).log("venue scan");
            recordSuccess(job);
        } catch (RuntimeException ex) {
            recordFailure(job, ex);
        }
    }

    /**
     * Persists every extracted show. {@code source} is computed once from the venue's own
     * calendar URL host -- {@code "venue:" + host} -- never taken from the extracted show's own
     * {@code source} field, matching the plan's explicit convention (mirrors {@code
     * BandSiteScraperService}'s {@code "band-site:" + host} for its own scrapes).
     */
    private int persist(String owner, String calendarUrl, List<Show> shows) {
        String source = "venue:" + host(calendarUrl);
        Instant discoveredAt = Instant.now();
        int saved = 0;
        for (Show show : shows) {
            if (show.getEventDateTime() == null) continue; // defense in depth, mirrors ScanUnitRunner#persistNew
            saved += showRepository.insertIfAbsent(owner, show.getArtistName(), show.getEventDateTime(),
                    show.getVenueName(), show.getVenueCity(), show.getPrice(), source, show.getTicketUrl(),
                    show.getKind().name(), discoveredAt);
        }
        return saved;
    }

    private void recordSuccess(VenueScanJob job) {
        Instant now = Instant.now();
        job.setStatus(JobStatus.SCHEDULED);
        job.setClaimedAt(null);
        job.setLastRunAt(now);
        job.setNextDueAt(now.plus(scanInterval));
        job.setAttempts(0);
        venueScanJobRepository.save(job);
    }

    /**
     * Deliberately does NOT touch {@code status} -- see the class javadoc. Backoff mirrors {@code
     * ScanPoller.nextDelay}: 10m * 2^attempts, capped at the venue's own {@link #scanInterval}.
     */
    private void recordFailure(VenueScanJob job, RuntimeException ex) {
        int attempts = job.getAttempts() + 1;

        log.atWarn().addKeyValue("owner", job.getOwner()).addKeyValue("venueId", job.getVenueId())
                .addKeyValue("attempts", attempts).setCause(ex).log("venue scan failed");

        job.setAttempts(attempts);
        job.setLastError(truncate(ex.getMessage()));
        job.setClaimedAt(null);
        job.setNextDueAt(Instant.now().plus(nextDelay(attempts)));
        venueScanJobRepository.save(job);
    }

    private Duration nextDelay(int attempts) {
        int shift = Math.min(attempts, 30);   // cap the shift; >2^30 * 10m already dwarfs any interval
        Duration exponential = BACKOFF_BASE.multipliedBy(1L << shift);
        return exponential.compareTo(scanInterval) < 0 ? exponential : scanInterval;
    }

    private static String host(String url) {
        try {
            return URI.create(url).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static String truncate(String message) {
        if (message == null) {
            return null;
        }
        return message.length() <= LAST_ERROR_MAX_LEN ? message : message.substring(0, LAST_ERROR_MAX_LEN);
    }
}
