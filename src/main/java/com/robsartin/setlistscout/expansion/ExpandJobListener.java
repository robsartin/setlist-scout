package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Keeps {@code expand_job} rows in sync with the catalog domain events: enqueues one job per
 * {@link RelationSource} on activation and cancels all of an artist's jobs on deactivation.
 * Expansion isn't location-sensitive, so unlike {@code scan.ScanJobListener} there's no
 * settings/fingerprint concern here.
 */
@Component
public class ExpandJobListener {

    private final ExpandJobRepository expandJobRepository;
    private final List<RelationSource> relationSources;
    private final ArtistRepository artistRepository;

    public ExpandJobListener(ExpandJobRepository expandJobRepository, List<RelationSource> relationSources,
            ArtistRepository artistRepository) {
        this.expandJobRepository = expandJobRepository;
        this.relationSources = relationSources;
        this.artistRepository = artistRepository;
    }

    @ApplicationModuleListener
    void onArtistActivated(ArtistActivated e) {
        // Tribute expansion is SEED-only: it hunts for tribute/cover bands of an original act,
        // which only makes sense for a hand-curated seed, not for an already-expanded artist.
        boolean isSeed = artistRepository.findByIdAndOwner(e.artistId(), e.owner())
                .map(a -> a.getStatus() == ArtistStatus.SEED)
                .orElse(false);

        for (RelationSource source : relationSources) {
            if (source.classification() == ArtistSource.TRIBUTE_EXPANSION && !isSeed) {
                continue;
            }
            // DB-level idempotent insert (ON CONFLICT DO NOTHING) rather than existsBy+save+catch:
            // @ApplicationModuleListener runs this whole loop in one transaction, and on an
            // IDENTITY-keyed table an uncaught DataIntegrityViolationException from a real unique-
            // constraint race aborts the ENTIRE transaction for every later iteration too (Postgres
            // "current transaction is aborted"). insertIfAbsent never throws on a duplicate, so the
            // loop always completes in a single pass.
            expandJobRepository.insertIfAbsent(e.owner(), e.artistId(), source.id(), Instant.now());
        }
    }

    @ApplicationModuleListener
    void onArtistDeactivated(ArtistDeactivated e) {
        expandJobRepository.deleteByOwnerAndArtistId(e.owner(), e.artistId());
    }
}
