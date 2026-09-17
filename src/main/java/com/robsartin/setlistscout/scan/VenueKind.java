package com.robsartin.setlistscout.scan;

/**
 * What kind of thing a followed venue is (issue #284, Films 1/3).
 *
 * <p>Set explicitly by the owner on the add form, never inferred. This one value drives three
 * separate behaviours, and each of them fails badly in a different way if it is wrong:
 * <ul>
 *   <li>which extraction prompt {@code BandSiteScraperService} uses -- a cinema calendar asked for
 *       "the act performing" yields nonsense;</li>
 *   <li>whether {@code VenueScanRunner} publishes {@code VenuePerformerSeen} -- pointed at a cinema,
 *       that event files every film title as a {@code PENDING_REVIEW} artist, hundreds a year,
 *       beside the musicians (the pollution class of #253 and #255);</li>
 *   <li>whether {@code ShowController#visibleToOwner} shows the result at all.</li>
 * </ul>
 * A URL or page-content heuristic would be wrong silently and at scale, which is why this is a
 * declared field with a database CHECK behind it (V38) rather than a guess.
 */
public enum VenueKind {
    /** A music/comedy venue: the calendar lists acts, and following it cross-filters to artists you follow. */
    LIVE,
    /** A cinema: the calendar lists films, and following it shows everything screening there. */
    CINEMA
}
