package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.PollerProperties;
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
 * Startup reconciler: enqueue one scan job per {@link ShowSource} for every active (SEED/APPROVED)
 * artist that doesn't already have jobs. Needed because artists activated before the job tables
 * existed (PR3b) never fired ArtistActivated, so they'd otherwise be invisible to the poller.
 * Idempotent via {@code insertIfAbsent} (safe to run every boot); {@code next_due_at} is jittered
 * across {@link PollerProperties#backfillSpread()} so enabling the poller doesn't stampede every
 * job on the first tick.
 */
@Component
@ConditionalOnProperty(name = "setlistscout.job-backfill-enabled", havingValue = "true", matchIfMissing = true)
public class ScanJobBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ScanJobBackfill.class);

    private final ArtistRepository artistRepository;
    private final ScanJobRepository scanJobRepository;
    private final List<ShowSource> showSources;
    private final SettingsService settingsService;
    private final PollerProperties properties;

    public ScanJobBackfill(ArtistRepository artistRepository, ScanJobRepository scanJobRepository,
                            List<ShowSource> showSources, SettingsService settingsService,
                            PollerProperties properties) {
        this.artistRepository = artistRepository;
        this.scanJobRepository = scanJobRepository;
        this.showSources = showSources;
        this.settingsService = settingsService;
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
            String fingerprint = settingsService.locationFingerprint(artist.getOwner());
            for (ShowSource source : showSources) {
                Instant dueAt = Instant.now().plusMillis(jitter(spreadMs));
                scanJobRepository.insertIfAbsent(artist.getOwner(), artist.getId(), source.id(),
                        dueAt, fingerprint);
                enqueued++;
            }
        }
        log.atInfo().addKeyValue("activeArtists", active.size())
                .addKeyValue("jobsConsidered", enqueued)
                .log("scan job backfill complete (insertIfAbsent -- existing jobs untouched)");
    }

    private long jitter(long spreadMs) {
        return spreadMs <= 0 ? 0 : ThreadLocalRandom.current().nextLong(spreadMs);
    }
}
