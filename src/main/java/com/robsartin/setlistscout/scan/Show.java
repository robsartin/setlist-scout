package com.robsartin.setlistscout.scan;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;

@Entity
@Table(name = "show_event", uniqueConstraints = @UniqueConstraint(
        columnNames = {"owner", "artistName", "eventDateTime", "venueName"}))
public class Show {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The user (email) who owns this show -- set at the persistence boundary. */
    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String artistName;

    @Column(nullable = false)
    private LocalDateTime eventDateTime;

    @Column(nullable = false)
    private String venueName;

    private String venueCity;

    /** Lowest available price found, if any. Null if the source didn't expose pricing. */
    private BigDecimal price;

    @Column(nullable = false)
    private String source; // e.g. "ticketmaster", "bandsintown", "venue-site:moodycenter"

    private String ticketUrl;

    /**
     * Music or comedy (#202). Every {@code Show} carries one -- {@link TicketmasterService} reads
     * Ticketmaster's own per-event classification, while {@link BandsintownService} and {@link
     * BandSiteScraperService} pass {@link Kind#MUSIC} explicitly, since those sources are
     * music-only/music-oriented and have no classification signal of their own to read (see their
     * call sites for why that's a true label, not an inferred one).
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Kind kind;

    @Column(nullable = false)
    private Instant discoveredAt = Instant.now();

    /**
     * Set when the owner hides this show from the default Shows list (issue #166); {@code null}
     * means visible. Survives re-scan by construction: {@code ScanUnitRunner#persistNew} skips a
     * show that already exists by natural key rather than refreshing it, and that check never
     * looks at this column, so a later scan re-discovering the same show never touches it.
     */
    private Instant hiddenAt;

    protected Show() {
        // JPA
    }

    public Show(String artistName, LocalDateTime eventDateTime, String venueName, String venueCity,
                BigDecimal price, String source, String ticketUrl, Kind kind) {
        this.artistName = artistName;
        this.eventDateTime = eventDateTime;
        this.venueName = venueName;
        this.venueCity = venueCity;
        this.price = price;
        this.source = source;
        this.ticketUrl = ticketUrl;
        this.kind = kind;
    }

    public Long getId() { return id; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getArtistName() { return artistName; }
    public LocalDateTime getEventDateTime() { return eventDateTime; }
    public String getVenueName() { return venueName; }
    public String getVenueCity() { return venueCity; }
    public BigDecimal getPrice() { return price; }
    public String getSource() { return source; }
    public String getTicketUrl() { return ticketUrl; }
    public Kind getKind() { return kind; }
    public Instant getDiscoveredAt() { return discoveredAt; }
    public Instant getHiddenAt() { return hiddenAt; }
    public void setHiddenAt(Instant hiddenAt) { this.hiddenAt = hiddenAt; }
    public boolean isHidden() { return hiddenAt != null; }

    /** #202: what kind of event this is. Comedy is the only non-music case in scope -- film (#204) is deliberately excluded. */
    public enum Kind { MUSIC, COMEDY }
}
