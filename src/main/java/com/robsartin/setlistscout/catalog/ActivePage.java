package com.robsartin.setlistscout.catalog;

import java.util.List;

/**
 * One page of the owner's active (SEED/APPROVED) list (issue #174): the rows to render, whether a
 * next/previous page exists, and the {@code normalizedName} cursor each control needs to fetch it.
 * Built by {@link ArtistPager}; see that class for the keyset paging logic itself.
 * <p>
 * {@code nextCursor}/{@code previousCursor} are {@code null} exactly when {@code hasNext}/{@code
 * hasPrevious} are {@code false} -- a template only ever reads a cursor from behind the boolean
 * that guards it (see {@code artists.html}'s {@code activeSection} fragment), so that pairing is
 * never checked separately from the boolean.
 * <p>
 * A plain record, like {@code review.ActionOutcome} -- no Spring, no I/O, unit-testable by
 * construction.
 */
public record ActivePage(List<Artist> artists, boolean hasNext, boolean hasPrevious,
                          String nextCursor, String previousCursor) {

    /**
     * The current-position sentence rendered visibly next to the pagination controls AND announced
     * through {@code #sr-status} on every htmx swap (issue #174's accessibility requirement) -- the
     * two deliberately say the same thing, same reasoning as {@code ActionOutcome}/candidates.html's
     * matching visible-empty-state and announcement text.
     */
    public String positionSummary() {
        if (artists.isEmpty()) {
            return "No active artists.";
        }
        if (artists.size() == 1) {
            return "Showing the only active artist: " + artists.get(0).getName() + ".";
        }
        String first = artists.get(0).getName();
        String last = artists.get(artists.size() - 1).getName();
        return "Showing " + artists.size() + " artists, " + first + " to " + last + ".";
    }
}
