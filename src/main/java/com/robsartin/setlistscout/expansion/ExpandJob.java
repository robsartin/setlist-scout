package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.shared.JobStatus;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One durable unit of expansion work: "check this relation source for this owner's artist."
 * One row per (owner, artistId, source) -- enqueued on ArtistActivated, cancelled on
 * ArtistDeactivated (see expansion.ExpandJobListener, PR3b).
 * Inert until the paced poller (PR4) starts draining SCHEDULED jobs.
 */
@Entity
@Table(name = "expand_job", uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "artist_id", "source"}))
public class ExpandJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The user (email) who owns this job -- set at the persistence boundary. */
    @Column(nullable = false)
    private String owner;

    @Column(name = "artist_id", nullable = false)
    private Long artistId;

    /** Which relation source this job checks, e.g. "lastfm", "discogs", "similar-llm", "tribute-llm". */
    @Column(nullable = false)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    /** Detail from the most recent failed run, if any. */
    private String lastError;

    private Instant lastRunAt;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    /** Set while a worker holds this job; null when idle. */
    private Instant claimedAt;

    protected ExpandJob() {
        // JPA
    }

    public ExpandJob(Long artistId, String source, JobStatus status, int attempts, Instant nextDueAt) {
        this.artistId = artistId;
        this.source = source;
        this.status = status;
        this.attempts = attempts;
        this.nextDueAt = nextDueAt;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public Long getArtistId() { return artistId; }
    public String getSource() { return source; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public Instant getNextDueAt() { return nextDueAt; }
    public void setNextDueAt(Instant nextDueAt) { this.nextDueAt = nextDueAt; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
}
