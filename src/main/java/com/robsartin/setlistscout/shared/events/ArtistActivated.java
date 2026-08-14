package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when an artist is activated in the catalog.
 * <p>
 * Published by: {@code catalog} module (or {@code scan} during seed).
 * Consumed by: {@code expansion} module ({@code ExpandJobListener.onArtistActivated}, enqueues
 * one {@code expand_job} per {@code RelationSource}) and {@code scan} module
 * ({@code ScanJobListener.onArtistActivated}, enqueues one {@code scan_job} per
 * {@code ShowSource}).
 */
public record ArtistActivated(
	String owner,
	Long artistId,
	String name
) {
}
