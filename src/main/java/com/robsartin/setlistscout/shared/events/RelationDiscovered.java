package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when expansion discovers a relationship between a base artist and a
 * related artist name -- one edge assertion, from one {@code RelationSource}.
 * <p>
 * Published by: {@code expansion} module.
 * Consumed by: {@code catalog.RelationDiscoveredListener}, which upserts the PENDING_REVIEW
 * to-artist {@code Artist} node and the corroborating {@code artist_edge} row in one transaction
 * (see {@code docs/explorations/2026-08-14-artist-graph-model.md}).
 */
public record RelationDiscovered(
	String owner,
	Long fromArtistId,
	String fromArtistName,
	String toArtistName,
	String type,
	String source,
	String note
) {
}
