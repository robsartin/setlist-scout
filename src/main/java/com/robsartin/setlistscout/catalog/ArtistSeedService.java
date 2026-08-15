package com.robsartin.setlistscout.catalog;

import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * Adds a band name to an owner's seed list, applied consistently everywhere a name can arrive:
 * the single "Add a band" box, an uploaded text file, and the startup seed-bands.txt import.
 * One place decides what counts as a real, new name (trimmed, non-blank, not a {@code #} comment,
 * not already present for the owner under any spelling variant {@link ArtistNameMatcher} would
 * treat as the same name).
 */
@Service
public class ArtistSeedService {

    private final ArtistRepository artistRepository;
    private final ArtistActivationService activationService;
    private final ArtistNameMatcher artistNameMatcher;

    public ArtistSeedService(ArtistRepository artistRepository, ArtistActivationService activationService,
                              ArtistNameMatcher artistNameMatcher) {
        this.artistRepository = artistRepository;
        this.activationService = activationService;
        this.artistNameMatcher = artistNameMatcher;
    }

    /**
     * Add {@code rawName} as a SEED artist for {@code owner}, unless it is blank or a {@code #}
     * comment. The name is trimmed before use, then checked against the owner's existing artists
     * via {@link ArtistNameMatcher} (issue #124) -- the same normalized-name equality {@code
     * RelationDiscoveredListener} uses for expansion-discovered names, so this path stops creating
     * duplicates for case/whitespace variants and the specific unicode dash/quote variants {@link
     * ArtistNameNormalizer} folds (e.g. "Foo-Bar" vs "Foo–Bar"). It is deliberately NOT a fix
     * for every punctuation difference -- "and" vs "&" is a word substitution, not a folded
     * codepoint, so "Tom Petty and The Heartbreakers" vs "Tom Petty & The Heartbreakers" still
     * creates two rows; that would need a fuzzier match than the normalizer intentionally provides
     * (see its class doc on why it stays conservative).
     * <p>
     * This path's semantics differ from {@code RelationDiscoveredListener}'s deliberately: that
     * listener silently reuses a match of ANY status, including REJECTED, because it's resolving
     * an automated discovery. Here the user (or an uploaded file, or startup seeding) is
     * explicitly asking to add this exact name -- silently swallowing that against a REJECTED or
     * still-PENDING_REVIEW row would look like the add did nothing, with no feedback. So: a match
     * against an already-ACTIVE row (SEED or APPROVED, {@link ArtistActivationService#isActive})
     * is treated as a true duplicate and no-ops. A match against an INACTIVE row (REJECTED or
     * PENDING_REVIEW) is treated as the caller deliberately un-rejecting/fast-tracking that artist
     * by name, and is reactivated to SEED through {@link ArtistActivationService#changeStatus} --
     * never a direct repository save -- so the activation event fires and the artist's scan/expand
     * jobs get enqueued exactly as they would for a brand-new seed.
     *
     * @return {@code true} if the call resulted in a newly-active SEED artist for the owner (a
     * fresh row, or an existing inactive row reactivated); {@code false} if it was skipped
     * (blank/comment) or the name already matched an artist that was already active.
     */
    public boolean addSeedIfNew(String owner, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.startsWith("#")) {
            return false;
        }

        Optional<ArtistNameStatusView> existing = artistNameMatcher.findExistingMatch(owner, name);
        if (existing.isPresent()) {
            ArtistNameStatusView match = existing.get();
            if (ArtistActivationService.isActive(match.getStatus())) {
                return false;
            }
            activationService.changeStatus(match.getId(), owner, ArtistStatus.SEED);
            return true;
        }

        Artist artist = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(owner);
        artistRepository.save(artist);
        activationService.onSeedCreated(artist);
        return true;
    }
}
