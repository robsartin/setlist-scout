package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.AbstractJob;
import com.robsartin.setlistscout.shared.JobStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One durable unit of scan work: "check this ShowSource for this owner's artist."
 * One row per (owner, artistId, source) -- enqueued on ArtistActivated, cancelled on
 * ArtistDeactivated, re-dued on SettingsChanged (see scan.ScanJobListener, PR3b).
 * Inert until the paced poller (PR4) starts draining SCHEDULED jobs.
 */
@Entity
@Table(name = "scan_job", uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "artist_id", "source"}))
public class ScanJob extends AbstractJob {

    protected ScanJob() {
        // JPA
    }

    public ScanJob(Long artistId, String source, JobStatus status, int attempts, Instant nextDueAt) {
        super(artistId, source, status, attempts, nextDueAt);
    }
}
