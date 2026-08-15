package com.robsartin.setlistscout.catalog;

import java.util.List;

/**
 * One artist discovered by {@link ArtistConnectionsService#discoverConnections} for the
 * Connections page (issue #112): a {@code PENDING_REVIEW} artist reachable within 2 hops of the
 * owner's active artists, with every path that reaches it.
 */
public record DiscoveredConnection(Long artistId, String artistName, List<ConnectionPath> paths) {
}
