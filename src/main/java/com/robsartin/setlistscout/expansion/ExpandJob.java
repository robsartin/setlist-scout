package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.shared.AbstractJob;
import com.robsartin.setlistscout.shared.JobStatus;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;

/**
 * One durable unit of expansion work: "check this relation source for this owner's artist."
 * One row per (owner, artistId, source) -- enqueued on ArtistActivated, cancelled on
 * ArtistDeactivated (see expansion.ExpandJobListener, PR3b).
 * Inert until the paced poller (PR4) starts draining SCHEDULED jobs.
 */
@Entity
@Table(name = "expand_job", uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "artist_id", "source"}))
public class ExpandJob extends AbstractJob {

    protected ExpandJob() {
        // JPA
    }

    public ExpandJob(Long artistId, String source, JobStatus status, int attempts, Instant nextDueAt) {
        super(artistId, source, status, attempts, nextDueAt);
    }
}
