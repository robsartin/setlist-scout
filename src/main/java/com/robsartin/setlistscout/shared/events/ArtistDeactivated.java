package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when an artist is deactivated in the catalog.
 * <p>
 * Published by: {@code catalog} module.
 * Consumed by: {@code expansion} module (inert until PR3b).
 */
public record ArtistDeactivated(
	String owner,
	Long artistId
) {
}
