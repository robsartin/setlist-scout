package com.robsartin.setlistscout.scan;

import java.util.List;

/**
 * What one Shows-page hide/unhide action (issue #166) tells the client: which element the
 * swapped-in {@code showsRegion} fragment should focus, and what the shared {@code #sr-status}
 * live region should announce.
 * <p>
 * Reuses the PATTERN issue #155 established for the Candidates page ({@code review.ActionOutcome})
 * rather than that class itself: it's typed to {@code catalog.Artist} and to that page's
 * relation-group paging, neither of which exists here (this page is one flat sorted table), and
 * {@code scan} taking on a dependency on {@code review} for a Shows-page concern would be a
 * backwards module relationship -- nothing in this app currently depends on {@code review}, and it
 * in turn depends on {@code catalog}/{@code expansion}, not {@code scan}.
 * <p>
 * Scoped down to just this page's two cases: {@code ROW} (the acted-on show is still rendered --
 * hide or unhide with the "show hidden" toggle on, since the row stays in the list either way,
 * just flipping which button it shows) and {@code ANCHOR} (the row just left the rendered list --
 * hiding with the toggle off -- or there's nothing more specific to focus). There is no
 * {@code TRIGGER} case: nothing on this page needs a swapped-in copy of its own trigger button
 * refocused.
 * <p>
 * Pure -- no Spring, no I/O -- and unit-testable in isolation, like {@code review.ActionOutcome}.
 */
public record ShowActionOutcome(Focus focus, Long showId, String message) {

    public enum Focus { ANCHOR, ROW }

    /**
     * Focus the row after {@code actedId} in {@code orderedRows} -- the list as the page renders
     * it, so "after" means visually below. Falls back to the anchor when the acted-on row has no
     * successor: it was last, the list is empty, or the id isn't in the list at all (e.g. it was
     * already hidden, so absent from a "visible only" ordering).
     * <p>
     * Callers must resolve this BEFORE mutating, while the acted-on row is still in the list.
     */
    public static ShowActionOutcome afterRow(List<Show> orderedRows, long actedId, String message) {
        Long successor = successorOf(orderedRows, actedId);
        return successor == null ? anchor(message) : row(successor, message);
    }

    /** Focus a specific show's action button by id -- used when the acted-on row stays rendered. */
    public static ShowActionOutcome row(Long id, String message) {
        return new ShowActionOutcome(Focus.ROW, id, message);
    }

    /** Focus the region's own anchor (the {@code #shows-region} fragment root). */
    public static ShowActionOutcome anchor(String message) {
        return new ShowActionOutcome(Focus.ANCHOR, null, message);
    }

    /**
     * Falls back to the anchor for when the row this outcome names turns out not to be rendered
     * after all (see {@code ShowController#focusable}). The anchor always renders -- it's the
     * fragment root -- so this keeps the "exactly one autofocus per response" invariant true by
     * construction rather than by convention.
     */
    public ShowActionOutcome downgradedToAnchor() {
        return focus == Focus.ANCHOR ? this : anchor(message);
    }

    /** Template predicate: is this the one show whose action button should carry {@code autofocus}? */
    public boolean focusesRow(Long id) {
        return focus == Focus.ROW && showId != null && showId.equals(id);
    }

    /** Template predicate: should the region's own anchor carry {@code autofocus}? */
    public boolean focusesAnchor() {
        return focus == Focus.ANCHOR;
    }

    private static Long successorOf(List<Show> orderedRows, long actedId) {
        for (int i = 0; i < orderedRows.size() - 1; i++) {
            Long id = orderedRows.get(i).getId();
            if (id != null && id == actedId) {
                return orderedRows.get(i + 1).getId();
            }
        }
        return null;
    }
}
