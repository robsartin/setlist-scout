package com.robsartin.setlistscout.catalog;

/**
 * Turns {@code (source, discoveredVia)} into one phrase a person can read (issue #279).
 *
 * <p>The artists page rendered {@link ArtistSource} raw -- {@code MEMBER_EXPANSION} -- which answers
 * "how did this get here" but not "why should I care". For the 1,162 member-expansion rows in the
 * owner's active list, <em>whose</em> member is the whole of the answer, and the parent was already
 * stored: every one of the 2,582 expansion-derived active artists carries a {@code discoveredVia}
 * (measured 2026-09-13). The rejected list had rendered it for a while; the active list had not.
 *
 * <p>One phrase rather than mechanism-plus-parent ({@code "MEMBER_EXPANSION -- found via Tom
 * Petty"}) because those say the same thing twice in two registers. "Member of Tom Petty" says it
 * once.
 *
 * <p>A pure static on a plain class -- no Spring, no entity, no I/O -- so the vocabulary is
 * unit-testable on its own and lives in exactly one place. {@link Artist#getProvenance()} is a
 * one-line delegate so a template can read {@code ${a.provenance}} without a {@code T(...)}
 * expression.
 */
public final class ArtistProvenance {

    private ArtistProvenance() {
    }

    /**
     * @param discoveredVia the artist this one was found through, or {@code null} -- which is the
     * normal, correct state for {@link ArtistSource#SEED_LIST} (nothing discovered it) and for
     * {@link ArtistSource#VENUE_EXPANSION} (see {@code VenuePerformerListener}, which inserts these
     * with no parent because the venue is not recorded on the artist row).
     * @return a phrase that never names a parent it does not have, and never renders an enum
     * spelling. An expansion source with no stored parent degrades to the mechanism alone rather
     * than producing {@code "Member of null"} or a dangling {@code "Member of"}.
     */
    public static String describe(ArtistSource source, String discoveredVia) {
        boolean hasParent = discoveredVia != null && !discoveredVia.isBlank();
        return switch (source) {
            case SEED_LIST -> "Seed list";
            case VENUE_EXPANSION -> "Seen at a followed venue";
            case MEMBER_EXPANSION -> hasParent ? "Member of " + discoveredVia.trim() : "Band member";
            case SIMILAR_EXPANSION -> hasParent ? "Similar to " + discoveredVia.trim() : "Similar artist";
            case TRIBUTE_EXPANSION -> hasParent ? "Tribute to " + discoveredVia.trim() : "Tribute act";
        };
    }
}
