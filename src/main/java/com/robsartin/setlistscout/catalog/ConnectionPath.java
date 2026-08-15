package com.robsartin.setlistscout.catalog;

/**
 * One provenance path connecting a discovered artist back to one of the owner's active
 * (SEED/APPROVED) artists, for the Connections page (issue #112, graph phase 3): either a direct
 * (1-hop) edge from {@code seedName}, or a 2-hop edge reached through an intermediate artist
 * ({@code viaArtistName}, {@code null} for a direct path). {@code edgeType}/{@code edgeSource}
 * are the LAST edge in the path (the one landing on the discovered artist) -- the "honesty of
 * provenance" this app's graph model is built around (see
 * {@code docs/explorations/2026-08-14-artist-graph-model.md}). A discovered artist reachable
 * multiple ways gets one {@code ConnectionPath} per distinct path, not a collapsed summary --
 * see {@link ArtistConnectionsService}.
 */
public record ConnectionPath(String seedName, String viaArtistName, int depth, String edgeType, String edgeSource) {
}
