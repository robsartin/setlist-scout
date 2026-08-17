package com.robsartin.setlistscout.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
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
     * <p>
     * The "not found" branch is a check-then-insert against the matcher above, so two
     * near-simultaneous calls for the same brand-new name can both pass that pre-check and then
     * race for real at the DB's {@code artist (owner, name)} unique constraint (issue #133; hit in
     * production on "Nebraska"). That race is resolved the same way {@code
     * RelationDiscoveredListener#resolveOrCreateToArtist} resolves the equivalent
     * expansion-discovery race: {@link ArtistRepository#insertIfAbsent} (a DB-level {@code INSERT
     * ... ON CONFLICT (owner, name) DO NOTHING}, never a raw {@code save()}) followed by {@link
     * ArtistRepository#findByOwnerAndName} to resolve the row. {@code insertIfAbsent}'s int return
     * (rows actually inserted) is how this call tells whether IT won the race: only the winner
     * calls {@link ArtistActivationService#onSeedCreated}; see that branch below for why the loser
     * must not. This method is {@code @Transactional} because {@code insertIfAbsent}, like every
     * other {@code insertIfAbsent} in this codebase, needs an ambient transaction supplied by its
     * caller -- and because that also satisfies the event-publish invariant (ADR-0024): {@code
     * onSeedCreated}'s publish only reaches the AFTER_COMMIT listeners if it happens inside a
     * transaction that goes on to commit.
     *
     * @return {@code true} if the call resulted in a newly-active SEED artist for the owner (a
     * fresh row this call itself inserted, or an existing inactive row this call reactivated);
     * {@code false} if it was skipped (blank/comment), the name already matched an artist that was
     * already active, or -- the race case -- a concurrent call won the insert for this exact
     * (owner, name) first. In that last case the name IS now a seed, just not because of this
     * call; from this call's point of view "did I cause a new seed" is correctly "no", the same
     * answer as every other case where it didn't.
     */
    @Transactional
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

        int inserted = artistRepository.insertIfAbsent(owner, name, ArtistNameNormalizer.normalize(name),
                ArtistSource.SEED_LIST.name(), ArtistStatus.SEED.name(), null, null, Instant.now());
        if (inserted == 0) {
            // Race loser (issue #133): between this call's matcher pre-check above and this
            // call's own insertIfAbsent, a concurrent call for this exact (owner, name) already
            // committed its row -- insertIfAbsent's ON CONFLICT made this call's insert a silent
            // no-op instead of throwing. That other call already has (or will have) its own
            // resolved Artist and calls onSeedCreated for it; doing so again here would
            // double-publish ArtistActivated for the SAME artist and double-enqueue its scan/expand
            // jobs for no reason (the enqueue side is itself idempotent, but there is no reason to
            // fire the event twice for one artist). So this call is done: it did not create
            // anything, and reports that honestly.
            return false;
        }

        Artist artist = artistRepository.findByOwnerAndName(owner, name)
                .orElseThrow(() -> new IllegalStateException(
                        "artist row missing immediately after this call's own insertIfAbsent for owner="
                                + owner + " name=\"" + name + "\""));
        activationService.onSeedCreated(artist);
        return true;
    }
}
