package com.robsartin.setlistscout.catalog;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "artist", uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "name"}))
public class Artist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The user (email) who owns this artist -- set at the persistence boundary. */
    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String name;

    /**
     * {@link ArtistNameNormalizer#normalize(String)} of {@link #name} (#176), persisted so name
     * matching is an indexed lookup rather than a scan-and-renormalize over the whole catalog.
     * <p>
     * Maintained by {@link #syncNormalizedName()} for the JPA path. The two NATIVE
     * {@code ArtistRepository#insertIfAbsent} call sites pass it explicitly instead, because
     * lifecycle callbacks do not fire for a native query -- relying on the callback alone would
     * leave nulls on exactly the path that creates every artist in production.
     */
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ArtistSource source;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ArtistStatus status;

    /** If this artist was found via expansion, which seed/artist led us here. Nullable for seed entries. */
    private String discoveredVia;

    /** Free-text note, e.g. "related act -- original artist deceased/defunct" */
    private String note;

    /** The artist's official site URL (from MusicBrainz, or user-edited); scraped for tour dates. Nullable. */
    private String officialSiteUrl;

    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    protected Artist() {
        // JPA
    }

    public Artist(String name, ArtistSource source, ArtistStatus status, String discoveredVia, String note) {
        this.name = name;
        this.source = source;
        this.status = status;
        this.discoveredVia = discoveredVia;
        this.note = note;
    }

    @PrePersist
    @PreUpdate
    void syncNormalizedName() {
        this.normalizedName = ArtistNameNormalizer.normalize(name);
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public ArtistSource getSource() { return source; }
    public ArtistStatus getStatus() { return status; }
    public void setStatus(ArtistStatus status) { this.status = status; }
    public String getDiscoveredVia() { return discoveredVia; }
    public String getNote() { return note; }
    public String getOfficialSiteUrl() { return officialSiteUrl; }
    public void setOfficialSiteUrl(String officialSiteUrl) { this.officialSiteUrl = officialSiteUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
