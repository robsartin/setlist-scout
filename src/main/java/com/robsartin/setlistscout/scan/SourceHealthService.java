package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.shared.SourceFailures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Decides when a show source has stopped answering, and lets the rest of the app ask (issue #265).
 *
 * <h2>The rule, and why each half of it is there</h2>
 * A source flips unhealthy when its current failure streak reaches {@link #FAILURE_THRESHOLD} AND
 * spans at least {@link #MIN_DISTINCT_ARTISTS} distinct artists, with every failure carrying the
 * same signature.
 * <ul>
 *   <li><b>Same signature</b> separates "dead" from "having a bad day". Bandsintown's outage was
 *       403, 403, 403 ... 2,857 times. A source returning a mix of 404s, timeouts and parse errors
 *       is behaving normally for a scraper.</li>
 *   <li><b>Distinct artists</b> is the guard against an over-eager kill switch, and it is the
 *       reason {@code band-site} is safe to track at all: that source scrapes a DIFFERENT host per
 *       artist, so one unscrapeable site failing repeatedly is a per-artist fact, not a source
 *       outage. It cannot flip the source however many times it is retried.</li>
 *   <li><b>Any success resets everything</b>, so a source that fails for one artist while working
 *       for others never accumulates. This is also the whole recovery mechanism -- see below.</li>
 * </ul>
 *
 * <h2>Recovery is automatic, on purpose</h2>
 * The 2026-08-25 outage was two failures stacked: the credential broke, and then <em>nothing
 * retried for 14 days</em> because every 403 had been recorded as a success and re-dued a full
 * interval out. A design where a human must clear the unhealthy flag would repeat the second half,
 * since that is exactly the manual step nobody knew to take. So the first successful call clears
 * the flag, with no button anywhere.
 *
 * <h2>Global, not per-owner</h2>
 * Bandsintown authenticates with one shared {@code app_id}: its 403 was every owner's 403 at once.
 * Keying health per owner would need the threshold crossed once per owner to notice a single broken
 * credential. Nothing here is owner-scoped, and nothing here may be shown per owner as though it
 * were.
 */
@Service
public class SourceHealthService {

    private static final Logger log = LoggerFactory.getLogger(SourceHealthService.class);

    /**
     * Consecutive same-signature failures before a source is considered down. 10 is roughly ten
     * minutes of ticks at the default batch size -- long enough that a brief provider wobble does
     * not trip it, short enough that six hours of 403s could never go unnoticed again.
     */
    static final int FAILURE_THRESHOLD = 10;

    /** A streak confined to one artist is a fact about that artist, never about the source. */
    static final int MIN_DISTINCT_ARTISTS = 2;

    private final SourceHealthRepository repository;
    private final Set<String> enabledSources;
    private final Clock clock;

    /**
     * The set of currently-unhealthy sources, refreshed by {@link #refresh()} once per poller tick.
     * Held so the hot path ({@code ScanPoller} asking "is this source up?" for every claimed job)
     * is a set lookup rather than a query per job.
     */
    private final AtomicReference<Set<String>> unhealthy = new AtomicReference<>(Set.of());

    // Explicit @Autowired: there are two constructors (this one plus the Clock test seam below),
    // so Spring's implicit single-constructor autowiring does not apply -- without this, startup
    // fails looking for a no-arg constructor. Same pitfall ScanPoller documents.
    @Autowired
    public SourceHealthService(SourceHealthRepository repository, List<ShowSource> showSources) {
        this(repository, enabledIds(showSources), Clock.systemUTC());
    }

    /**
     * The ids of the sources actually wired into this application context -- which is exactly the
     * set that {@code setlistscout.sources.<id>=false} (#139) removes from, since that flag drops
     * the bean entirely.
     *
     * <p>Null ids are skipped rather than collected: {@code Collectors.toUnmodifiableSet} rejects
     * null, and a source without an id is unusable regardless -- {@code ScanUnitRunner} matches jobs
     * with {@code s.id().equals(sourceId)} and would never select it.
     */
    private static Set<String> enabledIds(List<ShowSource> showSources) {
        return showSources.stream()
                .map(ShowSource::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Test seam: a fixed clock and an explicit enabled-source set. */
    SourceHealthService(SourceHealthRepository repository, Set<String> enabledSources, Clock clock) {
        this.repository = repository;
        this.enabledSources = enabledSources;
        this.clock = clock;
    }

    /** Re-read which sources are down. Called once per poller tick, not per job. */
    public void refresh() {
        unhealthy.set(unhealthyDetail().stream()
                .map(SourceHealth::getSource)
                .collect(Collectors.toUnmodifiableSet()));
    }

    /** @return false when {@code sourceId} is currently considered down. */
    public boolean isHealthy(String sourceId) {
        return !unhealthy.get().contains(sourceId);
    }

    /** @return the currently-unhealthy source ids, as of the last {@link #refresh()}. */
    public Set<String> unhealthySources() {
        return unhealthy.get();
    }

    /**
     * Full rows for display -- the Shows banner.
     *
     * <p>Filtered to sources that are actually ENABLED right now. A source switched off with
     * {@code setlistscout.sources.<id>=false} (#139) has no {@link ShowSource} bean, so
     * {@code ScanUnitRunner} returns early for it without ever reaching the call -- meaning it can
     * record neither a failure nor a success, and a stale unhealthy row could never be cleared. That
     * is not hypothetical: Bandsintown is switched off in production right now, precisely BECAUSE it
     * was returning 403 to everything. Without this filter, re-enabling it, watching it fail, then
     * switching it off again would leave a banner nobody could dismiss and its jobs churning on the
     * 30-minute probe re-due forever.
     *
     * <p>A disabled source is therefore treated as healthy everywhere: not banner-worthy (the owner
     * turned it off deliberately -- that is not news), and not excluded from scheduling (its jobs
     * cost one early return each and re-due on the normal interval). The row is left in place, so
     * re-enabling the source resumes exactly where it left off until the next call decides.
     */
    public List<SourceHealth> unhealthyDetail() {
        return repository.findByHealthyFalse().stream()
                .filter(h -> enabledSources.contains(h.getSource()))
                .toList();
    }

    /**
     * One call to {@code sourceId} for {@code artistId} failed. Extends the streak and flips the
     * source unhealthy if this failure is the one that crosses both thresholds.
     */
    public void recordFailure(String sourceId, Long artistId, Throwable cause) {
        Instant now = clock.instant();
        String signature = SourceFailures.signatureOf(cause);
        repository.recordFailure(sourceId, artistId, signature, truncate(cause), now);
        int flipped = repository.markUnhealthyIfPastThreshold(
                sourceId, FAILURE_THRESHOLD, MIN_DISTINCT_ARTISTS, now);
        if (flipped > 0) {
            log.atError().addKeyValue("source", sourceId).addKeyValue("signature", signature)
                    .addKeyValue("threshold", FAILURE_THRESHOLD)
                    .log("source marked UNHEALTHY -- every recent call failed the same way; "
                            + "scheduling for this source is now a single probe per tick");
            refresh();
        }
    }

    /**
     * One call to {@code sourceId} succeeded. Clears any streak, and clears the unhealthy flag if it
     * was set -- this is the recovery path, and it needs no human.
     */
    public void recordSuccess(String sourceId) {
        boolean wasDown = !isHealthy(sourceId);
        repository.recordSuccess(sourceId, clock.instant());
        if (wasDown) {
            log.atWarn().addKeyValue("source", sourceId)
                    .log("source RECOVERED -- a probe succeeded; normal scheduling resumes");
            refresh();
        }
    }

    private static String truncate(Throwable cause) {
        String message = cause == null ? null : cause.getMessage();
        if (message == null) {
            return null;
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
