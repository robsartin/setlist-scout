package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
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

    private static final Logger log = LoggerFactory.getLogger(ExpandJobListener.class);

    private final ExpandJobRepository expandJobRepository;
    private final List<RelationSource> relationSources;

    public ExpandJobListener(ExpandJobRepository expandJobRepository, List<RelationSource> relationSources) {
        this.expandJobRepository = expandJobRepository;
        this.relationSources = relationSources;
    }

    @ApplicationModuleListener
    void onArtistActivated(ArtistActivated e) {
        for (RelationSource source : relationSources) {
            if (expandJobRepository.existsByOwnerAndArtistIdAndSource(e.owner(), e.artistId(), source.id())) {
                continue; // already enqueued for this (owner, artist, source)
            }
            ExpandJob job = new ExpandJob(e.artistId(), source.id(), JobStatus.SCHEDULED, 0, Instant.now());
            job.setOwner(e.owner());
            try {
                expandJobRepository.save(job);
            } catch (DataIntegrityViolationException ex) {
                // Lost a race against the (owner, artist_id, source) unique constraint: a redelivery
                // of this durable event (or a concurrent activation) already enqueued this job.
                // Same outcome as the existsBy guard above, just discovered at insert time -- a
                // no-op, not an error.
                log.atDebug().addKeyValue("owner", e.owner()).addKeyValue("artistId", e.artistId())
                        .addKeyValue("source", source.id()).log("expand job already enqueued concurrently");
            }
        }
    }

    @ApplicationModuleListener
    void onArtistDeactivated(ArtistDeactivated e) {
        expandJobRepository.deleteByOwnerAndArtistId(e.owner(), e.artistId());
    }
}
