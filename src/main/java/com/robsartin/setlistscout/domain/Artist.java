package com.robsartin.setlistscout.domain;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "artist", uniqueConstraints = @UniqueConstraint(columnNames = "name"))
public class Artist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

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
    public String getName() { return name; }
    public ArtistSource getSource() { return source; }
    public ArtistStatus getStatus() { return status; }
    public void setStatus(ArtistStatus status) { this.status = status; }
    public String getDiscoveredVia() { return discoveredVia; }
    public String getNote() { return note; }
    public Instant getCreatedAt() { return createdAt; }
}
