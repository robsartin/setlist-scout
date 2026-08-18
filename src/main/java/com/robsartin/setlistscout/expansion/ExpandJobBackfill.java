package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.PollerProperties;
import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.SharedScanOwner;
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
 * Startup reconciler: enqueue one expand job per {@link RelationSource} for every active
 * (SEED/APPROVED) artist that doesn't already have jobs. Mirrors {@code scan.ScanJobBackfill} --
 * see there for the full rationale (artists activated before PR3b's job tables existed never
 * fired ArtistActivated, so they'd otherwise be invisible to the poller; the #195 readiness fix
 * that dropped {@code ApplicationRunner} in favor of a one-shot {@code @Scheduled} method; why the
 * transaction is demarcated via {@link TransactionTemplate} instead of a class-level
 * {@code @Transactional}). Idempotent via a batched {@code INSERT ... ON CONFLICT DO NOTHING};
 * {@code next_due_at} is jittered across {@link PollerProperties#backfillSpread()}. Tribute expand
 * jobs stay SEED-only, matching {@code ExpandJobListener#onArtistActivated}: tribute/cover-band
 * expansion only makes sense for a hand-curated seed, not for an already-expanded artist.
 */
@Component
@ConditionalOnProperty(name = "setlistscout.job-backfill-enabled", havingValue = "true", matchIfMissing = true)
public class ExpandJobBackfill {

    private static final Logger log = LoggerFactory.getLogger(ExpandJobBackfill.class);

    /** See {@code ScanJobBackfill#BATCH_SIZE} for the rationale. */
    private static final int BATCH_SIZE = 500;

    private final ArtistRepository artistRepository;
    private final ExpandJobRepository expandJobRepository;
    private final List<RelationSource> relationSources;
    private final PollerProperties properties;
    private final JdbcTemplate jdbcTemplate;
    private final TransactionTemplate transactionTemplate;

    public ExpandJobBackfill(ArtistRepository artistRepository, ExpandJobRepository expandJobRepository,
                              List<RelationSource> relationSources, PollerProperties properties,
                              JdbcTemplate jdbcTemplate, TransactionTemplate transactionTemplate) {
        this.artistRepository = artistRepository;
        this.expandJobRepository = expandJobRepository;
        this.relationSources = relationSources;
        this.properties = properties;
        this.jdbcTemplate = jdbcTemplate;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Fires once, effectively immediately, on the scheduler's own thread -- see
     * {@code ScanJobBackfill#runOnStartup} for why this never blocks
     * {@code ApplicationReadyEvent}/readiness.
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

        List<PendingExpandJob> pending = new ArrayList<>();
        for (Artist artist : active) {
            // #163: same guard as ExpandJobListener. findByStatusIn has NO owner filter, so this
            // startup backfill sees shared-scan artists too; without this it would re-enqueue the
            // expansion jobs the listener guard exists to prevent, on every restart.
            if (SharedScanOwner.isSharedScanKey(artist.getOwner())) {
                continue;
            }

            for (RelationSource source : relationSources) {
                if (!source.appliesTo(artist.getStatus())) {
                    continue;
                }
                Instant dueAt = Instant.now().plusMillis(jitter(spreadMs));
                pending.add(new PendingExpandJob(artist.getOwner(), artist.getId(), source.id(), dueAt));
            }
        }
        insertBatched(pending);

        log.atInfo().addKeyValue("activeArtists", active.size())
                .addKeyValue("jobsConsidered", pending.size())
                .log("expand job backfill complete (batched INSERT ... ON CONFLICT DO NOTHING -- existing jobs untouched)");
    }

    private void insertBatched(List<PendingExpandJob> pending) {
        for (int start = 0; start < pending.size(); start += BATCH_SIZE) {
            insertChunk(pending.subList(start, Math.min(start + BATCH_SIZE, pending.size())));
        }
    }

    private void insertChunk(List<PendingExpandJob> chunk) {
        if (chunk.isEmpty()) {
            return;
        }
        StringBuilder sql = new StringBuilder(
                "INSERT INTO expand_job (owner, artist_id, source, status, attempts, next_due_at) VALUES ");
        Object[] params = new Object[chunk.size() * 4];
        for (int i = 0; i < chunk.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append("(?, ?, ?, 'SCHEDULED', 0, ?)");
            PendingExpandJob job = chunk.get(i);
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

    private record PendingExpandJob(String owner, Long artistId, String source, Instant nextDueAt) {
    }
}
