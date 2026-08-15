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
 * Scans the owner's full artist list and normalizes in Java rather than pushing the comparison
 * into SQL, so there is exactly one implementation of "what counts as the same name" ({@link
 * ArtistNameNormalizer}) instead of a Java copy and a SQL copy that can drift apart -- the same
 * drift that inflated the issue's own first live-profiling pass (13 reported pairs vs. 3 real
 * ones). Acceptable at this app's scale (a personal tool, not a high-throughput SaaS); revisit
 * with a persisted/indexed match-name column if per-owner artist counts ever make an O(n) scan
 * per discovered relation a real cost.
 * <p>
 * This is a best-effort pre-check, same as the {@code existsByOwnerAndNameIgnoreCase} pre-check
 * it supersedes for the expansion path: a genuine race between two concurrent discoveries of
 * different-case/-punctuation spellings of the same new name could still both pass this check
 * before either commits. The DB's exact-match {@code (owner, name)} constraint remains the
 * backstop for a same-spelling race; a near-duplicate-spelling race is not DB-enforced.
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
        String target = ArtistNameNormalizer.normalize(candidateName);
        return artistRepository.findByOwner(owner).stream()
                .filter(view -> ArtistNameNormalizer.normalize(view.getName()).equals(target))
                .findFirst();
    }
}
