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
     * comment. The name is trimmed before use.
     *
     * <h2>Write first, then read -- issue #179</h2>
     * This method does NOT check whether the name already exists before inserting. It attempts
     * {@link ArtistRepository#insertIfAbsent} straight away and lets the database's
     * {@code UNIQUE (owner, normalized_name)} constraint (added by
     * {@code V21__unique_artist_normalized_name}) decide, reading only afterwards and only when the
     * insert was absorbed. That inverts what used to be a check-then-insert:
     * <ul>
     *   <li>The #133 race is <em>impossible</em> rather than compensated for. Two near-simultaneous
     *       calls for the same brand-new name used to be able to both pass an
     *       {@link ArtistNameMatcher} pre-check and then race for real at the constraint (hit in
     *       production on "Nebraska"). With no pre-check there is no window: both calls reach the
     *       insert, and Postgres serializes them.</li>
     *   <li>The #118 duplicate-variant guard moves out of application code and into the database.
     *       The pre-check could only ever be best-effort -- it caught a spelling variant that had
     *       already committed, never one committing concurrently. The constraint catches both.</li>
     * </ul>
     * The matcher is still used, just after the write instead of before it: when the insert is
     * absorbed, it resolves WHICH existing row this name collided with, so the status branch below
     * can run. Reading a row the database has already told us exists is not a check-then-act.
     *
     * <h2>What happens on each outcome</h2>
     * <ul>
     *   <li>Inserted (returned 1): a brand-new SEED artist, resolved by exact {@code name} -- the
     *       row this call itself just created carries exactly that string -- and announced via
     *       {@link ArtistActivationService#onSeedCreated} so its scan/expand jobs enqueue. Returns
     *       {@code true}.</li>
     *   <li>Absorbed (returned 0) and the existing row is ACTIVE (SEED or APPROVED,
     *       {@link ArtistActivationService#isActive}): a true duplicate, no-op, returns
     *       {@code false}. This also covers the race loser -- the winner's row is a fresh SEED, so
     *       the loser correctly reports "I did not cause a new seed" and does not double-publish
     *       {@code ArtistActivated} for someone else's insert.</li>
     *   <li>Absorbed and the existing row is INACTIVE (REJECTED, PENDING_REVIEW or REMOVED): the
     *       caller is deliberately un-rejecting / fast-tracking / re-adding that artist by name, so
     *       it is reactivated to SEED through {@link ArtistActivationService#changeStatus} -- never
     *       a direct repository save -- and the activation event fires exactly as it would for a
     *       brand-new seed. Returns {@code true}.</li>
     * </ul>
     * This path's semantics differ from {@code RelationDiscoveredListener}'s deliberately: that
     * listener silently reuses a match of ANY status, including REJECTED, because it is resolving
     * an automated discovery. Here the user (or an uploaded file, or startup seeding) is explicitly
     * asking to add this exact name -- silently swallowing that against a REJECTED or still-PENDING
     * row would look like the add did nothing, with no feedback.
     * <p>
     * One behaviour genuinely improves as a side effect: if this call loses a race to a
     * {@code RelationDiscoveredListener} insert of the same artist, it now finds that
     * PENDING_REVIEW row and promotes it to SEED, instead of returning {@code false} and leaving
     * the artist the user explicitly asked for sitting in the review queue.
     * <p>
     * Matching stays as conservative as {@link ArtistNameNormalizer} is: case, whitespace, and the
     * unicode dash/quote variants it folds (e.g. "Foo-Bar" vs "Foo-Bar" with an en dash) are the
     * same name; a word substitution like "and" vs "&amp;" is not, so "Tom Petty and The
     * Heartbreakers" and "Tom Petty &amp; The Heartbreakers" remain two rows.
     * <p>
     * {@code @Transactional} because {@code insertIfAbsent}, like every other
     * {@code insertIfAbsent} in this codebase, needs an ambient transaction supplied by its
     * caller -- and because that also satisfies the event-publish invariant (ADR-0024):
     * {@code onSeedCreated}'s publish only reaches the AFTER_COMMIT listeners if it happens inside
     * a transaction that goes on to commit.
     *
     * @return {@code true} if the call resulted in a newly-active SEED artist for the owner (a
     * fresh row this call itself inserted, or an existing inactive row this call reactivated);
     * {@code false} if it was skipped (blank/comment), or the name already belonged to an artist
     * that was already active -- including the race case, where the name IS now a seed, just not
     * because of this call.
     */
    @Transactional
    public boolean addSeedIfNew(String owner, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty() || name.startsWith("#")) {
            return false;
        }

        int inserted = artistRepository.insertIfAbsent(owner, name, ArtistNameNormalizer.normalize(name),
                ArtistSource.SEED_LIST.name(), ArtistStatus.SEED.name(), null, null, Instant.now());
        if (inserted > 0) {
            Artist artist = artistRepository.findByOwnerAndName(owner, name)
                    .orElseThrow(() -> new IllegalStateException(
                            "artist row missing immediately after this call's own insertIfAbsent for owner="
                                    + owner + " name=\"" + name + "\""));
            activationService.onSeedCreated(artist);
            return true;
        }

        // The insert was absorbed, so the database has already established that a row for this
        // owner and normalized name exists -- under this spelling or another. Resolve which.
        ArtistNameStatusView existing = existingMatch(owner, name);
        if (ArtistActivationService.isActive(existing.getStatus())) {
            return false;
        }
        activationService.changeStatus(existing.getId(), owner, ArtistStatus.SEED);
        return true;
    }

    /**
     * The row {@code insertIfAbsent} just conflicted against. Not an {@code Optional}: the insert
     * having been absorbed by {@code ON CONFLICT (owner, normalized_name)} is proof the row exists,
     * and this call reads it in the same transaction. Empty here would mean the constraint and the
     * matcher disagree about what "the same name" is -- unresolvable at runtime and worth failing
     * on loudly rather than silently reporting "nothing was added".
     */
    private ArtistNameStatusView existingMatch(String owner, String name) {
        Optional<ArtistNameStatusView> match = artistNameMatcher.findExistingMatch(owner, name);
        return match.orElseThrow(() -> new IllegalStateException(
                "insertIfAbsent reported a conflict but no artist matches it, for owner=" + owner
                        + " name=\"" + name + "\" normalizedName=\"" + ArtistNameNormalizer.normalize(name) + "\""));
    }
}
