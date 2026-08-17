package com.robsartin.setlistscout.catalog;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One name from a bulk upload, waiting to be seeded (#177).
 * <p>
 * Mirrors the durable-job shape (claim lease, attempts, backoff) without extending
 * {@code shared.AbstractJob}, which requires a non-null {@code artist_id} that an import row by
 * definition does not have yet.
 */
@Entity
@Table(name = "artist_import")
public class ArtistImport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    /** The name exactly as the owner supplied it -- what gets seeded and what they see. */
    @Column(nullable = false)
    private String name;

    /** {@link ArtistNameNormalizer#normalize} of {@link #name}; the partial unique index keys on it. */
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ArtistImportStatus status = ArtistImportStatus.PENDING;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    /** Set while a worker holds this row; null when idle. */
    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "next_due_at", nullable = false)
    private Instant nextDueAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ArtistImport() {
        // JPA
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public ArtistImportStatus getStatus() { return status; }
    public void setStatus(ArtistImportStatus status) { this.status = status; }
    public int getAttempts() { return attempts; }
    public void setAttempts(int attempts) { this.attempts = attempts; }
    public String getLastError() { return lastError; }
    public void setLastError(String lastError) { this.lastError = lastError; }
    public Instant getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Instant claimedAt) { this.claimedAt = claimedAt; }
    public Instant getNextDueAt() { return nextDueAt; }
    public void setNextDueAt(Instant nextDueAt) { this.nextDueAt = nextDueAt; }
    public Instant getCreatedAt() { return createdAt; }
}
