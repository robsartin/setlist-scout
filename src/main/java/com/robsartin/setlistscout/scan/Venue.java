package com.robsartin.setlistscout.scan;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A venue whose calendar the owner follows as a show source (#206).
 * <p>
 * Mirrors {@code catalog.ArtistImport}'s shape (#177): a {@code protected} no-arg constructor for
 * JPA, a package-private test-fixture constructor, and no setters on the identity fields
 * ({@code owner}/{@code name}/{@code normalizedName}) -- production code creates rows exclusively
 * through {@link VenueRepository#insertIfAbsent}'s native {@code INSERT}, never
 * {@code new Venue(...)} + {@code save()}. {@code calendarUrl} does get a setter: unlike a name,
 * a URL is not part of the venue's identity and the owner can correct it after creation.
 */
@Entity
@Table(name = "venue")
public class Venue {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    /** The name exactly as the owner supplied it -- what they see. */
    @Column(nullable = false)
    private String name;

    /** {@link com.robsartin.setlistscout.catalog.ArtistNameNormalizer#normalize} of {@link #name}; the unique index keys on it. */
    @Column(name = "normalized_name", nullable = false)
    private String normalizedName;

    @Column(name = "calendar_url", nullable = false)
    private String calendarUrl;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Venue() {
        // JPA
    }

    /**
     * Test-fixture constructor: package-private, for same-package tests that need to hand-build a
     * populated row without a real database. Mirrors {@code catalog.ArtistImport}'s equivalent
     * constructor.
     */
    Venue(String owner, String name, String normalizedName, String calendarUrl) {
        this.owner = owner;
        this.name = name;
        this.normalizedName = normalizedName;
        this.calendarUrl = calendarUrl;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getName() { return name; }
    public String getNormalizedName() { return normalizedName; }
    public String getCalendarUrl() { return calendarUrl; }
    public void setCalendarUrl(String calendarUrl) { this.calendarUrl = calendarUrl; }
    public Instant getCreatedAt() { return createdAt; }
}
