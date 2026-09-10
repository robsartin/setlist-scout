package com.robsartin.setlistscout.scan;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * One row per show source: is it currently answering at all (issue #265)?
 *
 * <p>Read-only from Java's side -- every write goes through {@link SourceHealthRepository}'s atomic
 * {@code INSERT ... ON CONFLICT} statements rather than load-mutate-save. The poller can run jobs
 * concurrently ({@code claimDue} is {@code FOR UPDATE SKIP LOCKED}), and a read-modify-write on a
 * single shared counter row is exactly the shape that loses increments under concurrency.
 */
@Entity
@Table(name = "source_health")
public class SourceHealth {

    @Id
    @Column(name = "source", nullable = false)
    private String source;

    @Column(name = "healthy", nullable = false)
    private boolean healthy = true;

    @Column(name = "consecutive_failures", nullable = false)
    private int consecutiveFailures;

    @Column(name = "streak_signature")
    private String streakSignature;

    @Column(name = "streak_artists", nullable = false)
    private int streakArtists;

    @Column(name = "last_artist_id")
    private Long lastArtistId;

    @Column(name = "last_success_at")
    private Instant lastSuccessAt;

    @Column(name = "last_failure_at")
    private Instant lastFailureAt;

    @Column(name = "last_error")
    private String lastError;

    @Column(name = "unhealthy_since")
    private Instant unhealthySince;

    protected SourceHealth() {
    }

    public String getSource() {
        return source;
    }

    public boolean isHealthy() {
        return healthy;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures;
    }

    public String getStreakSignature() {
        return streakSignature;
    }

    public int getStreakArtists() {
        return streakArtists;
    }

    public Instant getLastSuccessAt() {
        return lastSuccessAt;
    }

    public Instant getLastFailureAt() {
        return lastFailureAt;
    }

    public String getLastError() {
        return lastError;
    }

    public Instant getUnhealthySince() {
        return unhealthySince;
    }
}
