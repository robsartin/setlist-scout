package com.robsartin.setlistscout.catalog;

import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Finds an owner's existing artist -- in ANY status -- whose name normalizes to the same match
 * form as a candidate name (issue #118). This is the guard {@code RelationDiscoveredListener}
 * needs in front of {@code ArtistRepository#insertIfAbsent}: that method's {@code ON CONFLICT
 * (owner, name)} is an exact-string match, so a rejected "Charlie Parker's Re-Boppers" does
 * nothing to stop a later "Charlie Parker's Re-boppers" from insertIfAbsent-ing its way in as a
 * brand-new PENDING_REVIEW row.
 * <p>
 * Performs one indexed {@link ArtistRepository#findFirstByOwnerAndNormalizedName} lookup rather
 * than scanning the owner's full artist list and re-normalizing every row in Java. The normalized
 * form is now stored on {@link Artist#getNormalizedName()} at write time instead of recomputed
 * here, so there is still exactly one implementation of "what counts as the same name"
 * ({@link ArtistNameNormalizer}) -- it just runs once, at write time, instead of once per row on
 * every read.
 * <p>
 * This retires the O(n)-per-discovered-relation scan that this class's doc used to accept as fine
 * "at this app's scale". {@code db.migration.V19__add_artist_normalized_name} (#176) is what makes
 * that possible: it adds the stored column and backfills it for every pre-existing row, and this
 * class is rewritten to query that column instead of scanning. The drift risk this doc used to
 * warn about -- a Java copy of the normalization logic drifting from a hand-rolled SQL copy --
 * still doesn't apply: an indexed equality lookup only ever compares one stored, Java-normalized
 * value against another Java-normalized value, so no SQL ever reimplements the folding rules.
 * <p>
 * This is a best-effort pre-check, same as the {@code existsByOwnerAndNameIgnoreCase} pre-check
 * it supersedes for the expansion path: a genuine race between two concurrent discoveries of
 * different-case/-punctuation spellings of the same new name could still both pass this check
 * before either commits. The DB's exact-match {@code (owner, name)} constraint remains the
 * backstop for a same-spelling race; a near-duplicate-spelling race is not DB-enforced.
 * <p>
 * Signature deliberately unchanged from the pre-#176 scan-based version: both production callers
 * ({@code ArtistSeedService}, {@code RelationDiscoveredListener}) and their tests depend only on
 * this method's behavior, not its mechanism.
 */
@Component
public class ArtistNameMatcher {

    private final ArtistRepository artistRepository;

    public ArtistNameMatcher(ArtistRepository artistRepository) {
        this.artistRepository = artistRepository;
    }

    /**
     * @return the owner's existing artist (in any status) whose name normalizes the same as
     * {@code candidateName}, if one exists.
     */
    public Optional<ArtistNameStatusView> findExistingMatch(String owner, String candidateName) {
        return artistRepository.findFirstByOwnerAndNormalizedName(
                owner, ArtistNameNormalizer.normalize(candidateName));
    }
}
