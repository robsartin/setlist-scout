package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when a scan run turns up at least one show for an artist -- the evidence
 * that the artist is a real, playing act rather than a name someone guessed.
 * <p>
 * Published by: {@code scan} module ({@code ScanUnitRunner#run}).
 * Consumed by: {@code expansion} module ({@code ExpandJobListener.onArtistShowsFound}).
 * <p>
 * Exists because of issue #254. Expansion used to recurse from any activated artist, so a single
 * wrong name match multiplied without limit: {@code Bill Bryson} the bluegrass bassist (a correct
 * member expansion) resolved on the next hop to {@code Bill Bryson} the travel author, and the
 * cluster below that grew to ~40 approved authors and 30 pending candidates -- every one of them
 * with zero shows. An artist that has been scanned and produced nothing is not evidence of
 * anything; recursing from it multiplies a guess. Expansion now waits for this event, so an
 * artist earns the right to be expanded from by actually having a show.
 * <p>
 * {@code status} is the {@code catalog.ArtistStatus} enum name as a {@code String}, matching
 * {@link ArtistActivated}'s convention so {@code shared} stays a leaf module.
 */
public record ArtistShowsFound(
	String owner,
	Long artistId,
	String name,
	String status
) {
}
