package com.robsartin.setlistscout.catalog;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * One credit: (artist) --[role]--> (work), as asserted by one source (#286).
 *
 * <p>A separate table from {@link ArtistEdge} rather than a widening of it, because
 * {@code artist_edge} FK-constrains both of its endpoints to {@code artist(id)} and a work is not
 * a performer. Everything else follows {@code ArtistEdge}: {@code role} and {@code source} are
 * plain strings rather than JPA enums, and the unique constraint includes {@code source} so two
 * sources asserting the same role corroborate rather than collide.
 *
 * <p>The role vocabulary -- {@code DIRECTED}, {@code ACTED_IN}, {@code WROTE_SCREENPLAY_FOR},
 * {@code PRODUCED} -- is segue's edge-type spelling, so the two graphs can be read together
 * without a translation layer.
 */
@Entity
@Table(name = "person_work_edge",
        uniqueConstraints = @UniqueConstraint(
                columnNames = {"owner", "artist_id", "work_id", "role", "source"}))
public class PersonWorkEdge {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(name = "artist_id", nullable = false)
    private Long artistId;

    @Column(name = "work_id", nullable = false)
    private Long workId;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected PersonWorkEdge() {
        // JPA
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public Long getArtistId() { return artistId; }
    public Long getWorkId() { return workId; }
    public String getRole() { return role; }
    public String getSource() { return source; }
    public Instant getCreatedAt() { return createdAt; }
}
