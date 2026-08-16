package com.robsartin.setlistscout.catalog;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "Which artists do these two owners both actively follow?" -- the input to a shared scan (#163).
 * <p>
 * The match runs through {@link ArtistNameNormalizer}, never string equality, and that is the whole
 * point of this class. Measured against production before it was written: Rob has 1,269 active
 * artists, David has 5, and they genuinely share 4 -- but an exact-name join finds 1, because
 * David's entries are lowercased on the second word ("Tom petty" vs "Tom Petty"). An exact join
 * returns a plausible-looking non-empty answer, which is exactly why it would have shipped.
 * <p>
 * The asymmetry is structural, not incidental: the intersection is bounded by the SMALLER list.
 */
@Component
public class SharedArtistFinder {

    /**
     * The same definition of "active" as {@code ArtistActivationService#isActive}. REJECTED,
     * REMOVED and PENDING_REVIEW are excluded on BOTH sides -- an artist one participant has
     * rejected is not one they want to be sold tickets to.
     */
    private static final List<ArtistStatus> ACTIVE = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    private final ArtistRepository artistRepository;

    public SharedArtistFinder(ArtistRepository artistRepository) {
        this.artistRepository = artistRepository;
    }

    /**
     * @return one name per artist active for BOTH owners, in {@code ownerA}'s spelling, sorted
     * case-insensitively, with no duplicates. Which side's spelling wins does not affect search
     * results -- Ticketmaster and Bandsintown keyword search are both case-insensitive.
     */
    public List<String> findSharedArtistNames(String ownerA, String ownerB) {
        Set<String> keysForB = new HashSet<>();
        for (Artist artist : artistRepository.findByOwnerAndStatusIn(ownerB, ACTIVE)) {
            keysForB.add(ArtistNameNormalizer.normalize(artist.getName()));
        }

        // putIfAbsent, not put: if ownerA holds two case-variant rows for one artist (the condition
        // V13 merged and #118 guards), they collapse to one result rather than being searched twice.
        Map<String, String> displayNameByKey = new LinkedHashMap<>();
        for (Artist artist : artistRepository.findByOwnerAndStatusIn(ownerA, ACTIVE)) {
            String key = ArtistNameNormalizer.normalize(artist.getName());
            if (keysForB.contains(key)) {
                displayNameByKey.putIfAbsent(key, artist.getName());
            }
        }

        return displayNameByKey.values().stream()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }
}
