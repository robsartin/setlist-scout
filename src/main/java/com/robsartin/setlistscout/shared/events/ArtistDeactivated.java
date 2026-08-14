package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when an artist is deactivated in the catalog.
 * <p>
 * Published by: {@code catalog} module.
 * Consumed by: {@code expansion} module ({@code ExpandJobListener.onArtistDeactivated}) and
 * {@code scan} module ({@code ScanJobListener.onArtistDeactivated}) — both cancel (delete) the
 * artist's pending jobs of their respective type.
 */
public record ArtistDeactivated(
	String owner,
	Long artistId
) {
}
