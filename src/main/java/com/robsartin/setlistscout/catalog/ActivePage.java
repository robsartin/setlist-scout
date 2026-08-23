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
                          String nextCursor, String previousCursor, String query) {

    /**
     * The current-position sentence rendered visibly next to the pagination controls AND announced
     * through {@code #sr-status} on every htmx swap (issue #174's accessibility requirement) -- the
     * two deliberately say the same thing, same reasoning as {@code ActionOutcome}/candidates.html's
     * matching visible-empty-state and announcement text.
     */
    /**
     * @param noun what this page is a page OF -- "active" on the artists list, "rejected" on the
     * Rejected page (#251). A parameter rather than a record component because it is a rendering
     * concern: the same page of rows says a different sentence depending on which list rendered
     * it, and nothing about the paging itself changes.
     */
    public String positionSummary(String noun) {
        // #250: the same sentence has to stay true when a search narrows the list. "No active
        // artists." while a query is active reads as "your list is empty" -- a different claim,
        // and a wrong one. Unfiltered wording is byte-for-byte what #174 shipped.
        boolean searching = ArtistSearchTerm.isSearch(query);
        String matching = searching ? " matching \"" + query.trim() + "\"" : "";
        if (artists.isEmpty()) {
            return searching
                    ? "No " + noun + " artists match \"" + query.trim() + "\"."
                    : "No " + noun + " artists.";
        }
        if (artists.size() == 1) {
            return "Showing the only " + noun + " artist" + matching + ": " + artists.get(0).getName() + ".";
        }
        String first = artists.get(0).getName();
        String last = artists.get(artists.size() - 1).getName();
        return "Showing " + artists.size() + " artists" + matching + ", " + first + " to " + last + ".";
    }
}
