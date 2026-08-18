package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.PollerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Startup reconciler: enqueue one scan job per {@link ShowSource} for every active (SEED/APPROVED)
 * artist that doesn't already have jobs. Needed because artists activated before the job tables
 * existed (PR3b) never fired ArtistActivated, so they'd otherwise be invisible to the poller.
 * Idempotent via a batched {@code INSERT ... ON CONFLICT DO NOTHING} (safe to run every boot);
 * {@code next_due_at} is jittered across {@link PollerProperties#backfillSpread()} so enabling the
 * poller doesn't stampede every job on the first tick.
 * <p>
 * <b>#195:</b> this is reconciliation, not a startup precondition -- {@code ScanJobListener}
 * already keeps jobs in sync during normal operation, and this only catches drift. It must never
 * gate readiness, so (unlike its PR4b origin) it no longer {@code implements ApplicationRunner}:
 * Boot's {@code callRunners()} blocks {@code ApplicationReadyEvent} -- and therefore
 * {@code ReadinessState.ACCEPTING_TRAFFIC} -- until every runner returns, and this used to do
 * ~thousands of individual inserts there, one per (artist, source), on a throttled instance.
 * Instead it fires once, off the startup path, via the app's existing {@code @Scheduled}/
 * {@code TaskScheduler} machinery (see {@code ScanPoller}) rather than importing a new async
 * mechanism such as {@code @EnableAsync}: {@link #runOnStartup()} is a {@code @Scheduled} method
 * with no repeat ({@code fixedDelay = Long.MAX_VALUE}, no {@code initialDelay} -- Spring still
 * runs a fixed-delay task's first execution immediately, then wouldn't schedule the next one for
 * ~292 million years), which Spring Boot excludes from {@code spring.main.lazy-initialization} the
 * same way it already does for {@code ScanPoller}/{@code ExpandPoller} (see application.yml's
 * lazy-init comment) -- so this fires on every boot without needing a {@code @Lazy(false)} escape
 * hatch.
 * <p>
 * {@link #run(ApplicationArguments)} stays the real, synchronous, directly-invocable entry point
 * -- {@code ScanJobBackfillTest}/{@code SharedScanGuardsTest} call it directly and assert on the
 * DB immediately after, so it has to actually do the work before returning, not merely schedule
 * it. Its {@code args} parameter is unused, kept only so those call sites didn't need to change.
 * Its transaction is demarcated explicitly via {@link TransactionTemplate} rather than a
 * class-level {@code @Transactional}: {@link #runOnStartup()} calls it as a plain same-bean method
 * call, which bypasses Spring's proxy-based {@code @Transactional} AOP entirely -- the same
 * self-invocation trap {@code ScanPoller}'s class doc documents -- so the transaction has to be
 * started explicitly (matching how {@code ExpandUnitRunner} already uses
 * {@code TransactionTemplate} for the same "must actually be transactional regardless of call
 * path" reason) rather than relying on a proxy that this call path never goes through.
 */
@Component
@ConditionalOnProperty(name = "setlistscout.job-backfill-enabled", havingValue = "true", matchIfMissing = true)
public class ScanJobBackfill {

    private static final Logger log = LoggerFactory.getLogger(ScanJobBackfill.class);

    /**
     * A few hundred rows per {@code INSERT} -- collapses thousands of round trips (and their
     * per-statement commit/fsync cost under the old one-row-at-a-time loop) into a handful,
     * comfortably under Postgres's/the driver's per-statement parameter limits.
     */
    private static final int BATCH_SIZE = 500;

    private final ArtistRepository artistRepository;
    private final ScanJobRepository scanJobRepository;
    private final List<ShowSource> showSources;
    private final PollerProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ScanJobBackfill(ArtistRepository artistRepository, ScanJobRepository scanJobRepository,
                            List<ShowSource> showSources, PollerProperties properties,
                            JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.artistRepository = artistRepository;
        this.scanJobRepository = scanJobRepository;
        this.showSources = showSources;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Fires once, effectively immediately, on the scheduler's own thread -- see the class doc for
     * why this never blocks {@code ApplicationReadyEvent}/readiness.
     */
    @Scheduled(fixedDelay = Long.MAX_VALUE)
    void runOnStartup() {
        run(new DefaultApplicationArguments());
    }

    /**
     * The real reconciliation logic, synchronous. See the class doc for why the transaction is
     * demarcated with {@link TransactionTemplate} here instead of a class-level
     * {@code @Transactional}.
     */
    public void run(ApplicationArguments args) {
        transactionTemplate.executeWithoutResult(status -> reconcile());
    }

    private void reconcile() {
        long spreadMs = properties.backfillSpread().toMillis();
        List<Artist> active = artistRepository.findByStatusIn(
                List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));

        List<PendingScanJob> pending = new ArrayList<>();
        for (Artist artist : active) {
            // #163: a shared scan is enqueued for the cheap sources only. This runs on every
            // application start and writes directly, so it needs the same policy ScanJobListener
            // applies -- guarding only the listener would be undone on each restart.
            for (ShowSource source : SharedScanSources.forOwner(artist.getOwner(), showSources)) {
                Instant dueAt = Instant.now().plusMillis(jitter(spreadMs));
                pending.add(new PendingScanJob(artist.getOwner(), artist.getId(), source.id(), dueAt));
            }
        }
        insertBatched(pending);

        log.atInfo().addKeyValue("activeArtists", active.size())
                .addKeyValue("jobsConsidered", pending.size())
                .log("scan job backfill complete (batched INSERT ... ON CONFLICT DO NOTHING -- existing jobs untouched)");
    }

    private void insertBatched(List<PendingScanJob> pending) {
        for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
            insertChunk(pending.subList(start, Math.min(start + BATCH_SIZE, pending.size())));
        }
    }

    private void insertChunk(List<PendingScanJob> chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(
                "INSERT INTO scan_job (owner, artist_id, source, status, attempts, next_due_at) VALUES ");
        Object[] params = new Object[chunk.size() * 4];
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?, ?, 'SCHEDULED', 0, ?)");
            PendingScanJob job = chunk.get(i);
            params[i * 4] = job.owner();
            params[i * 4 + 1] = job.artistId();
            params[i * 4 + 2] = job.source();
            params[i * 4 + 3] = Timestamp.from(job.nextDueAt());
        }
        sql.append(" ON CONFLICT (owner, artist_id, source) DO NOTHING");
        jdbcTemplate.update(sql.toString(), params);
    }

    private long jitter(long spreadMs) {
        return spreadMs <= 0 ? 0 : ThreadLocalRandom.current().nextLong(spreadMs);
    }

    private record PendingScanJob(String owner, Long artistId, String source, Instant nextDueAt) {
    }
}
