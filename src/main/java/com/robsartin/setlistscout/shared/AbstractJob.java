package com.robsartin.setlistscout.shared;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Shared mapping for the durable job entities (scan.ScanJob, expansion.ExpandJob): one row per
 * (owner, artistId, source) representing a unit of work to check some source for an owner's
 * artist. {@code @MappedSuperclass} maps these fields into each subclass's own table -- there is
 * no shared "job" table, so this introduces no DB/Flyway change.
 */
@MappedSuperclass
public abstract class AbstractJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    @Column(nullable = false)
    private long version;

    /** The user (email) who owns this job -- set at the persistence boundary. */
    @Column(nullable = false)
    private String owner;

    @Column(name = "artist_id", nullable = false)
    private Long artistId;

    /** Which source this job checks, e.g. "ticketmaster", "lastfm", "similar-llm". */
    @Column(nullable = false)
    private String source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    /** Detail from the most recent failed run, if any. */
    @Column(columnDefinition = "text")
    private String lastError;

    private Instant lastRunAt;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    /** Set while a worker holds this job; null when idle. */
    private Instant claimedAt;

    protected AbstractJob() {
        // JPA
    }

    protected AbstractJob(Long artistId, String source, JobStatus status, int attempts, Instant nextDueAt) {
        this.artistId = artistId;
        this.source = source;
        this.status = status;
        this.attempts = attempts;
        this.nextDueAt = nextDueAt;
    }

    public Long getId() { return id; }
    public long getVersion() { return version; }
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
