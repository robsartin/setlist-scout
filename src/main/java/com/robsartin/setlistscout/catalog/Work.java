package com.robsartin.setlistscout.catalog;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * A work -- a film, for now -- that a person the owner follows had a hand in (#286).
 *
 * <p>Named for the ontology rather than for film. In segue's model, from which this borrows its
 * vocabulary, {@code WORK} is a node kind and "director" is an <em>edge</em>, never a node type;
 * nothing on this entity is film-specific, and a play or an album would fit it unchanged.
 *
 * <h2>Identity is the QID</h2>
 * Not title+year, which is what #284 assumed. {@code P577} (publication date) is multi-valued in
 * Wikidata -- one statement per country release -- so 38 of Martin Scorsese's 112 films report
 * more than one year and 4 report none. Keyed on title+year, a third of films would exist twice.
 * {@link #releaseYear} is {@code MIN(P577)}, the original release, and it is <em>nullable</em>.
 *
 * <p>{@link #normalizedTitle} and {@link #releaseYear} together are the key a screening is matched
 * on -- a cinema calendar has never heard of a QID -- but that match is 3/3's job, not this one's.
 *
 * <p>Follows {@code Venue}'s shape: {@code protected} no-arg constructor for JPA, no setters on
 * the identity fields, and rows created through {@link WorkRepository#insertIfAbsent}'s native
 * INSERT rather than {@code new Work(...)} + {@code save()}.
 */
@Entity
@Table(name = "work", uniqueConstraints = @UniqueConstraint(columnNames = {"owner", "wikidata_qid"}))
public class Work {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String owner;

    @Column(name = "wikidata_qid", nullable = false)
    private String wikidataQid;

    /** The title as Wikidata's English label states it -- what the owner sees. */
    @Column(nullable = false)
    private String title;

    /** {@link ArtistNameNormalizer#normalize(String)} of {@link #title}; half of the match key. */
    @Column(name = "normalized_title", nullable = false)
    private String normalizedTitle;

    /** The earliest stated release year, or null when Wikidata states none. */
    @Column(name = "release_year")
    private Integer releaseYear;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Work() {
        // JPA
    }

    /** Test-fixture constructor: package-private, mirroring {@code Venue}'s. */
    Work(String owner, String wikidataQid, String title, String normalizedTitle, Integer releaseYear) {
        this.owner = owner;
        this.wikidataQid = wikidataQid;
        this.title = title;
        this.normalizedTitle = normalizedTitle;
        this.releaseYear = releaseYear;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public String getWikidataQid() { return wikidataQid; }
    public String getTitle() { return title; }
    public String getNormalizedTitle() { return normalizedTitle; }
    public Integer getReleaseYear() { return releaseYear; }
    public Instant getCreatedAt() { return createdAt; }

    /** "Dune (1984)", or just "Dune" when no year is known -- the form a listing is read against. */
    public String getTitleWithYear() {
        return releaseYear == null ? title : title + " (" + releaseYear + ")";
    }
}
