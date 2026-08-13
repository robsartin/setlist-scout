package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when a candidate band is discovered during expansion.
 * <p>
 * Published by: {@code expansion} module.
 * Consumed by: {@code catalog} module.
 */
public record CandidateDiscovered(
	String owner,
	String name,
	String sourceType,
	String discoveredVia,
	String note
) {
}
