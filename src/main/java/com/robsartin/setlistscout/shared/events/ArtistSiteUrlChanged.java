package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when an artist's cached official-site URL is REPLACED by a different,
 * previously non-null value (issue #248).
 * <p>
 * Published by: {@code catalog} module ({@code ArtistSiteUrlService#recordOfficialSiteUrl}) --
 * only when there was a real prior value and it actually changed. Setting a URL for the first
 * time (old value null) or re-setting the same value publishes nothing: there is no PREVIOUS
 * source to retire in either case.
 * <p>
 * Consumed by: {@code scan} module ({@code ShowRetirementListener}), which deletes that artist's
 * VISIBLE {@code show_event} rows whose source is the band-site source derived from {@code
 * oldUrl}'s host -- the old site is no longer scraped, so anything found there is stale by
 * definition. Never touches {@code ticketmaster}/{@code venue:} rows (they have nothing to do
 * with the artist's own site) or a show the owner has hidden ({@code hidden_at} set) -- deleting a
 * hidden row would remove the natural-key placeholder that keeps a rescan from reinserting it
 * unhidden (see {@code HiddenShowSurvivesRescanTest}).
 */
public record ArtistSiteUrlChanged(
	String owner,
	Long artistId,
	String oldUrl
) {
}
