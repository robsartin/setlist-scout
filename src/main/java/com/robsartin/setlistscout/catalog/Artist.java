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

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getName() { return name; }
    public ArtistSource getSource() { return source; }
    public ArtistStatus getStatus() { return status; }
    public void setStatus(ArtistStatus status) { this.status = status; }
    public String getDiscoveredVia() { return discoveredVia; }
    public String getNote() { return note; }
    public String getOfficialSiteUrl() { return officialSiteUrl; }
    public void setOfficialSiteUrl(String officialSiteUrl) { this.officialSiteUrl = officialSiteUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
