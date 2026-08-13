package com.robsartin.setlistscout.expansion;

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

    public ExpandJobListener(ExpandJobRepository expandJobRepository, List<RelationSource> relationSources) {
        this.expandJobRepository = expandJobRepository;
        this.relationSources = relationSources;
    }

    @ApplicationModuleListener
    void onArtistActivated(ArtistActivated e) {
        for (RelationSource source : relationSources) {
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
