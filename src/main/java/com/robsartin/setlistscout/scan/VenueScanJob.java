package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.JobStatus;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One durable unit of scan work for a followed venue's calendar page (#206): "check this venue's
 * calendar_url for new shows." One row per (owner, venue) -- created by a later task (the
 * poller/enqueue path); this task only establishes the entity, table, and claim query.
 * <p>
 * Mirrors the durable-job shape ({@code shared.AbstractJob}: claim lease, attempts, backoff)
 * without extending it -- {@code AbstractJob} requires a non-null {@code artist_id}, which a
 * venue job by definition does not have. Same call {@code catalog.ArtistImport} made in #177;
 * read that class first.
 */
@Entity
@Table(name = "venue_scan_job")
public class VenueScanJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(name = "venue_id", nullable = false)
    private Long venueId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** Set while a worker holds this job; null when idle. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected VenueScanJob() {
        // JPA
    }

    /**
     * Test-fixture constructor: package-private, for same-package tests that need to hand-build a
     * populated row without a real database. Mirrors {@code catalog.ArtistImport}'s equivalent
     * constructor. {@code owner}/{@code venueId} have no setter -- identity fixed at creation.
     */
    VenueScanJob(String owner, Long venueId, JobStatus status, int attempts, Instant nextDueAt) {
        this.owner = owner;
        this.venueId = venueId;
        this.status = status;
        this.attempts = attempts;
        this.nextDueAt = nextDueAt;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public Long getVenueId() { return venueId; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Instant getNextDueAt() { return nextDueAt; }
    public void setNextDueAt(Instant nextDueAt) { this.nextDueAt = nextDueAt; }
    public Instant getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(Instant lastRunAt) { this.lastRunAt = lastRunAt; }
    public Instant getCreatedAt() { return createdAt; }
}
