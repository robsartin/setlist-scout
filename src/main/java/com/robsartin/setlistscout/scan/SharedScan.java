package com.robsartin.setlistscout.scan;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Two users sharing a scan (#163): identity and participants only.
 * <p>
 * Location, radius and window are NOT here on purpose -- they live in {@code search_settings}
 * under {@link #getOwnerKey()}. That is what lets {@code SettingsService}, the settings form, and
 * the existing {@code SettingsChanged -> ScanJobListener.onSettingsChanged} re-due behaviour apply
 * to a shared scan with no new code. Duplicating location columns here would forfeit all of it.
 * <p>
 * Two participant columns rather than a join table: the app needs exactly one pairing, and
 * {@code SharedArtistFinder#findSharedArtistNames} is a two-owner function. N-way membership would
 * change the finder too, so it is not modelled speculatively.
 */
@Entity
@Table(name = "shared_scan")
public class SharedScan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The synthetic owner string this scan's artists, settings, jobs and shows are keyed by. */
    @Column(name = "owner_key", nullable = false, unique = true)
    private String ownerKey;

    @Column(name = "owner_a", nullable = false)
    private String ownerA;

    @Column(name = "owner_b", nullable = false)
    private String ownerB;

    /** Display name, e.g. "Rob & David". */
    @Column(nullable = false)
    private String label;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected SharedScan() {
        // JPA
    }

    public SharedScan(String ownerKey, String ownerA, String ownerB, String label) {
        this.ownerKey = ownerKey;
        this.ownerA = ownerA;
        this.ownerB = ownerB;
        this.label = label;
    }

    public Long getId() { return id; }
    public String getOwnerKey() { return ownerKey; }
    public String getOwnerA() { return ownerA; }
    public String getOwnerB() { return ownerB; }
    public String getLabel() { return label; }
    public Instant getCreatedAt() { return createdAt; }

    /** True if {@code email} is one of the two participants. Case-insensitive: OIDC casing must not decide access. */
    public boolean includes(String email) {
        return email != null && (email.equalsIgnoreCase(ownerA) || email.equalsIgnoreCase(ownerB));
    }

    /** The other participant's address, from {@code email}'s point of view; null if {@code email} isn't a participant. */
    public String otherParticipant(String email) {
        if (email == null) return null;
        if (email.equalsIgnoreCase(ownerA)) return ownerB;
        if (email.equalsIgnoreCase(ownerB)) return ownerA;
        return null;
    }
}
