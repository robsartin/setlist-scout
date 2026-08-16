package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.Artist;

import java.util.List;

/**
 * What one Candidates-page action tells the client (issue #155): which element the swapped-in
 * fragment should focus, and what the persistent screen-reader status region should announce.
 * <p>
 * Every action on that page swaps {@code #candidates-app} with {@code outerHTML}, which destroys
 * the element holding focus -- the browser then drops focus to {@code <body>}, costing a keyboard
 * user a full re-tab through the nav and the sidebar after every decision. The fix is for the
 * server to name the element to focus and mark it {@code autofocus}: htmx focuses it after the
 * swap (verified against the vendored 2.0.3 build), so no client-side JavaScript is needed.
 * <p>
 * Pure -- no Spring, no I/O -- and unit-testable in isolation, like {@link CandidateGroups}.
 */
public record ActionOutcome(Focus focus, Long artistId, String decision, String message) {

    /**
     * {@code ROW} focuses one candidate's Approve/Reject button; {@code ANCHOR} focuses the current
     * group (or the empty state when nothing is pending); {@code NONE} emits no {@code autofocus}
     * at all, for actions whose own trigger survives the swap and is re-focused by htmx's built-in
     * id-based restore.
     */
    public enum Focus { NONE, ANCHOR, ROW }

    /**
     * Focus the row after {@code actedId} in {@code orderedGroupRows} -- the list as the page
     * renders it, so "after" means visually below. Falls back to the group anchor when the acted-on
     * row has no successor (it was last, the list is empty, or the id isn't there at all).
     * <p>
     * Callers must resolve this BEFORE mutating, while the acted-on row is still in the list.
     */
    public static ActionOutcome afterRow(List<Artist> orderedGroupRows, long actedId, String decision,
                                         String message) {
        Long successor = successorOf(orderedGroupRows, actedId);
        return successor == null ? anchor(message) : new ActionOutcome(Focus.ROW, successor, decision, message);
    }

    /** Focus the current group's anchor -- the auto-advance, bulk-action and navigation landing spot. */
    public static ActionOutcome anchor(String message) {
        return new ActionOutcome(Focus.ANCHOR, null, null, message);
    }

    /** Emit no {@code autofocus}: the triggering element survives the swap and htmx re-focuses it by id. */
    public static ActionOutcome keepFocus(String message) {
        return new ActionOutcome(Focus.NONE, null, null, message);
    }

    /**
     * Downgrades {@code ROW} to the group anchor, for when the chosen successor turns out not to be
     * rendered after all (a concurrent decision in another tab). Keeps the "exactly one autofocus
     * per response" invariant true by construction rather than by convention.
     */
    public ActionOutcome withoutRowFocus() {
        return focus == Focus.ROW ? anchor(message) : this;
    }

    /** Template predicate: is this the one button that should carry {@code autofocus}? */
    public boolean focusesRow(Long id, String decision) {
        return focus == Focus.ROW && artistId.equals(id) && this.decision.equals(decision);
    }

    /** Template predicate: should the group anchor (or the empty state) carry {@code autofocus}? */
    public boolean focusesAnchor() {
        return focus == Focus.ANCHOR;
    }

    private static Long successorOf(List<Artist> orderedGroupRows, long actedId) {
        for (int i = 0; i < orderedGroupRows.size() - 1; i++) {
            Long id = orderedGroupRows.get(i).getId();
            if (id != null && id == actedId) {
                return orderedGroupRows.get(i + 1).getId();
            }
        }
        return null;
    }
}
