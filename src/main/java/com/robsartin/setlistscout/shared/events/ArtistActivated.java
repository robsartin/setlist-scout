package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when an artist is activated in the catalog.
 * <p>
 * Published by: {@code catalog} module (or {@code scan} during seed).
 * Consumed by: {@code expansion} module.
 */
public record ArtistActivated(
	String owner,
	Long artistId,
	String name
) {
}
