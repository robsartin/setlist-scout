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
public record ActionOutcome(Focus focus, Long artistId, String decision, String triggerId, String message) {

    /**
     * {@code ROW} focuses one candidate's Approve/Reject button; {@code ANCHOR} focuses the current
     * group (or the empty state when nothing is pending); {@code TRIGGER} focuses the swapped-in
     * copy of the button that issued the request, named by its stable {@code id}.
     * <p>
     * There is deliberately no "focus nothing" value: every htmx response on this page carries
     * exactly one {@code autofocus}, and a full page render is expressed as a {@code null} outcome
     * rather than as a kind of outcome.
     */
    public enum Focus { ANCHOR, ROW, TRIGGER }

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
        return successor == null ? anchor(message) : new ActionOutcome(Focus.ROW, successor, decision, null, message);
    }

    /** Focus the current group's anchor -- the auto-advance, bulk-action and navigation landing spot. */
    public static ActionOutcome anchor(String message) {
        return new ActionOutcome(Focus.ANCHOR, null, null, null, message);
    }

    /**
     * Focus the swapped-in copy of the button that triggered the action, named by the stable
     * {@code id} it carries in the markup (e.g. {@code expand-now}).
     * <p>
     * The obvious alternative -- leave these responses without any {@code autofocus} and let htmx's
     * built-in id-based restore put focus back on the trigger, which the swap re-renders -- CANNOT
     * fire here. Measured in a browser against the vendored 2.0.3 build: {@code
     * hx-disabled-elt="find button"} sets {@code disabled} on the trigger when the request starts,
     * which BLURS it, so {@code document.activeElement} is already {@code <body>} by the time the
     * swap runs; htmx's restore is gated on the previously-focused element having LEFT the document
     * and {@code <body>} never does, so it is skipped and focus stays on {@code <body>}. The
     * settle-task {@code focus([autofocus])} has no such gate -- it runs on the freshly inserted,
     * re-enabled button -- so naming the trigger here is what actually keeps focus on it.
     */
    public static ActionOutcome trigger(String triggerId, String message) {
        return new ActionOutcome(Focus.TRIGGER, null, null, triggerId, message);
    }

    /**
     * Falls back to the group anchor, for when the element this outcome names turns out not to be
     * rendered after all: a successor row decided in another tab, or a trigger whose own form isn't
     * on the page. The anchor always renders -- when nothing is pending, the empty state answers the
     * same predicate -- so this keeps the "exactly one autofocus per response" invariant true by
     * construction rather than by convention.
     */
    public ActionOutcome downgradedToAnchor() {
        return focus == Focus.ANCHOR ? this : anchor(message);
    }

    /** Template predicate: is this the one button that should carry {@code autofocus}? */
    public boolean focusesRow(Long id, String decision) {
        return focus == Focus.ROW && artistId.equals(id) && this.decision.equals(decision);
    }

    /** Template predicate: should the group anchor (or the empty state) carry {@code autofocus}? */
    public boolean focusesAnchor() {
        return focus == Focus.ANCHOR;
    }

    /** Template predicate: is {@code id} the trigger button that should carry {@code autofocus}? */
    public boolean focusesTrigger(String id) {
        return focus == Focus.TRIGGER && triggerId.equals(id);
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
