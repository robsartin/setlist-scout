package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.PollerProperties;
import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Startup reconciler: enqueue one expand job per {@link RelationSource} for every active
 * (SEED/APPROVED) artist that doesn't already have jobs. Mirrors {@code scan.ScanJobBackfill} --
 * see there for the full rationale (artists activated before PR3b's job tables existed never
 * fired ArtistActivated, so they'd otherwise be invisible to the poller). Idempotent via
 * {@code insertIfAbsent}; {@code next_due_at} is jittered across
 * {@link PollerProperties#backfillSpread()}. Tribute expand jobs stay SEED-only, matching
 * {@code ExpandJobListener#onArtistActivated}: tribute/cover-band expansion only makes sense for
 * a hand-curated seed, not for an already-expanded artist.
 */
@Component
@ConditionalOnProperty(name = "setlistscout.job-backfill-enabled", havingValue = "true", matchIfMissing = true)
public class ExpandJobBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ExpandJobBackfill.class);

    private final ArtistRepository artistRepository;
    private final ExpandJobRepository expandJobRepository;
    private final List<RelationSource> relationSources;
    private final PollerProperties properties;

    public ExpandJobBackfill(ArtistRepository artistRepository, ExpandJobRepository expandJobRepository,
                              List<RelationSource> relationSources, PollerProperties properties) {
        this.artistRepository = artistRepository;
        this.expandJobRepository = expandJobRepository;
        this.relationSources = relationSources;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        long spreadMs = properties.backfillSpread().toMillis();
        int enqueued = 0;
        List<Artist> active = artistRepository.findByStatusIn(
                List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));
        for (Artist artist : active) {
            for (RelationSource source : relationSources) {
                if (source.classification() == ArtistSource.TRIBUTE_EXPANSION
                        && artist.getStatus() != ArtistStatus.SEED) {
                    continue;
                }
                Instant dueAt = Instant.now().plusMillis(jitter(spreadMs));
                expandJobRepository.insertIfAbsent(artist.getOwner(), artist.getId(), source.id(), dueAt);
                enqueued++;
            }
        }
        log.atInfo().addKeyValue("activeArtists", active.size())
                .addKeyValue("jobsConsidered", enqueued)
                .log("expand job backfill complete (insertIfAbsent -- existing jobs untouched)");
    }

    private long jitter(long spreadMs) {
        return spreadMs <= 0 ? 0 : ThreadLocalRandom.current().nextLong(spreadMs);
    }
}
