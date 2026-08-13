package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import com.robsartin.setlistscout.shared.events.SettingsChanged;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * Keeps {@code scan_job} rows in sync with the catalog/settings domain events: enqueues one job
 * per {@link ShowSource} on activation, cancels all of an artist's jobs on deactivation, and
 * re-dues + refreshes the location fingerprint on every job when settings change.
 */
@Component
public class ScanJobListener {

    private static final Logger log = LoggerFactory.getLogger(ScanJobListener.class);

    private final ScanJobRepository scanJobRepository;
    private final List<ShowSource> showSources;
    private final SettingsService settingsService;

    public ScanJobListener(ScanJobRepository scanJobRepository, List<ShowSource> showSources,
                            SettingsService settingsService) {
        this.scanJobRepository = scanJobRepository;
        this.showSources = showSources;
        this.settingsService = settingsService;
    }

    @ApplicationModuleListener
    void onArtistActivated(ArtistActivated e) {
        String locationFingerprint = settingsService.locationFingerprint(e.owner());
        for (ShowSource source : showSources) {
            if (scanJobRepository.existsByOwnerAndArtistIdAndSource(e.owner(), e.artistId(), source.id())) {
                continue; // already enqueued for this (owner, artist, source)
            }
            ScanJob job = new ScanJob(e.artistId(), source.id(), JobStatus.SCHEDULED, 0, Instant.now(),
                    locationFingerprint);
            job.setOwner(e.owner());
            try {
                scanJobRepository.save(job);
            } catch (DataIntegrityViolationException ex) {
                // Lost a race against the (owner, artist_id, source) unique constraint: a redelivery
                // of this durable event (or a concurrent activation) already enqueued this job.
                // Same outcome as the existsBy guard above, just discovered at insert time -- a
                // no-op, not an error.
                log.atDebug().addKeyValue("owner", e.owner()).addKeyValue("artistId", e.artistId())
                        .addKeyValue("source", source.id()).log("scan job already enqueued concurrently");
            }
        }
    }

    @ApplicationModuleListener
    void onArtistDeactivated(ArtistDeactivated e) {
        scanJobRepository.deleteByOwnerAndArtistId(e.owner(), e.artistId());
    }

    @ApplicationModuleListener
    void onSettingsChanged(SettingsChanged e) {
        String locationFingerprint = settingsService.locationFingerprint(e.owner());
        for (ScanJob job : scanJobRepository.findByOwner(e.owner())) {
            job.setNextDueAt(Instant.now());
            job.setLocationFingerprint(locationFingerprint);
            scanJobRepository.save(job);
        }
    }
}
