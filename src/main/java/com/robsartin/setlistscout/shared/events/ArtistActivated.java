package com.robsartin.setlistscout.shared.events;

/**
 * Domain event published when an artist is activated in the catalog.
 * <p>
 * Published by: {@code catalog} module (or {@code scan} during seed).
 * Consumed by: {@code expansion} module ({@code ExpandJobListener.onArtistActivated}, enqueues
 * one {@code expand_job} per {@code RelationSource}, gating source eligibility off {@link #status}
 * rather than querying {@code catalog.ArtistRepository} back) and {@code scan} module
 * ({@code ScanJobListener.onArtistActivated}, enqueues one {@code scan_job} per
 * {@code ShowSource}).
 * <p>
 * {@code status} is the {@code catalog.ArtistStatus} enum name (a {@code String} here, not the
 * enum itself, so {@code shared} stays a leaf module with no dependency on {@code catalog}) --
 * see {@code RelationDiscovered#type} for the same primitive-payload convention.
 */
public record ArtistActivated(
	String owner,
	Long artistId,
	String name,
	String status
) {
}
