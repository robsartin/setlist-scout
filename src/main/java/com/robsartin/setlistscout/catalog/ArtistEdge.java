package com.robsartin.setlistscout.catalog;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A single relationship assertion: (fromArtist) --[type]--> (toArtist), as asserted by one
 * {@code RelationSource} (see {@code expansion.source.RelationSource}), at a point in time,
 * with an optional free-text note and weight. See
 * {@code docs/explorations/2026-08-14-artist-graph-model.md} for the model rationale.
 *
 * <p>Deliberately not the same row as {@link Artist} -- {@code type}/{@code source} here are
 * plain strings (mirroring {@code RelationSource#classification().name()} and
 * {@code RelationSource#id()}), not JPA enums, since the set of valid sources is defined by
 * whatever {@code RelationSource} implementations exist, not a fixed enum on this entity.
 *
 * <p>Multiple edges can exist for the same {@code (owner, fromArtistId, toArtistId, type)} as
 * long as {@code source} differs -- that's corroboration, not duplication (the
 * {@code artist_edge_unique} constraint from {@code V10__artist_edge.sql} is on all five columns
 * together, source included).
 */
@Entity
@Table(name = "artist_edge",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"owner", "from_artist_id", "to_artist_id", "type", "source"}))
public class ArtistEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(name = "from_artist_id", nullable = false)
    private Long fromArtistId;

    @Column(name = "to_artist_id", nullable = false)
    private Long toArtistId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String source;

    @Column(columnDefinition = "text")
    private String note;

    private Double weight;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ArtistEdge() {
        // JPA
    }

    public ArtistEdge(String owner, Long fromArtistId, Long toArtistId, String type, String source,
                       String note, Double weight, Instant createdAt) {
        this.owner = owner;
        this.fromArtistId = fromArtistId;
        this.toArtistId = toArtistId;
        this.type = type;
        this.source = source;
        this.note = note;
        this.weight = weight;
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public Long getFromArtistId() { return fromArtistId; }
    public Long getToArtistId() { return toArtistId; }
    public String getType() { return type; }
    public String getSource() { return source; }
    public String getNote() { return note; }
    public Double getWeight() { return weight; }
    public Instant getCreatedAt() { return createdAt; }
}
