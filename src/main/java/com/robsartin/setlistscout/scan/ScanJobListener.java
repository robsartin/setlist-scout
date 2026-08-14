package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import com.robsartin.setlistscout.shared.events.SettingsChanged;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Keeps {@code scan_job} rows in sync with the catalog/settings domain events: enqueues one job
 * per {@link ShowSource} on activation, cancels all of an artist's jobs on deactivation, and
 * re-dues every job when settings change.
 */
@Component
public class ScanJobListener {

    private final ScanJobRepository scanJobRepository;
    private final List<ShowSource> showSources;

    public ScanJobListener(ScanJobRepository scanJobRepository, List<ShowSource> showSources) {
        this.scanJobRepository = scanJobRepository;
        this.showSources = showSources;
    }

    @ApplicationModuleListener
    void onArtistActivated(ArtistActivated e) {
        for (ShowSource source : showSources) {
            // DB-level idempotent insert (ON CONFLICT DO NOTHING) rather than existsBy+save+catch:
            // @ApplicationModuleListener runs this whole loop in one transaction, and on an
            // IDENTITY-keyed table an uncaught DataIntegrityViolationException from a real unique-
            // constraint race aborts the ENTIRE transaction for every later iteration too (Postgres
            // "current transaction is aborted"). insertIfAbsent never throws on a duplicate, so the
            // loop always completes in a single pass.
            scanJobRepository.insertIfAbsent(e.owner(), e.artistId(), source.id(), Instant.now());
        }
    }

    @ApplicationModuleListener
    void onArtistDeactivated(ArtistDeactivated e) {
        scanJobRepository.deleteByOwnerAndArtistId(e.owner(), e.artistId());
    }

    @ApplicationModuleListener
    void onSettingsChanged(SettingsChanged e) {
        // One version-bumping bulk UPDATE rather than load-all-and-save-each: re-dues every job to
        // run now AND bumps version, so an in-flight poller reschedule conflicts instead of
        // silently clobbering this re-due (the PR4a review's lost-update fix).
        scanJobRepository.redueAll(e.owner(), Instant.now());
    }
}
