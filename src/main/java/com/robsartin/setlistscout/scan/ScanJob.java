package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.JobStatus;
import jakarta.persistence.*;

import java.time.Instant;

/**
 * One durable unit of scan work: "check this ShowSource for this owner's artist."
 * One row per (owner, artistId, source) -- enqueued on ArtistActivated, cancelled on
 * ArtistDeactivated, re-dued on SettingsChanged (see scan.ScanJobListener, PR3b).
 * Inert until the paced poller (PR4) starts draining SCHEDULED jobs.
 */
@Entity
@Table(name = "scan_job", uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "artist_id", "source"}))
public class ScanJob {

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

    /** Which ShowSource this job checks, e.g. "ticketmaster", "bandsintown", "venue-site:moodycenter". */
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

    /** Stable hash of the owner's search location at last enqueue/re-due; see SettingsService.locationFingerprint. */
    @Column(name = "location_fingerprint")
    private String locationFingerprint;

    protected ScanJob() {
        // JPA
    }

    public ScanJob(Long artistId, String source, JobStatus status, int attempts, Instant nextDueAt,
                    String locationFingerprint) {
        this.artistId = artistId;
        this.source = source;
        this.status = status;
        this.attempts = attempts;
        this.nextDueAt = nextDueAt;
        this.locationFingerprint = locationFingerprint;
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
    public String getLocationFingerprint() { return locationFingerprint; }
    public void setLocationFingerprint(String locationFingerprint) { this.locationFingerprint = locationFingerprint; }
}
