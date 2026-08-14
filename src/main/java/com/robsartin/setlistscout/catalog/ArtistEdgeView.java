package com.robsartin.setlistscout.catalog;

import java.time.Instant;

/**
 * Display row for one {@link ArtistEdge} on the read-only graph-validation page (issue #111):
 * the *other* endpoint's name already resolved from its artist id, plus the edge's own fields.
 */
public record ArtistEdgeView(String otherArtistName, String type, String source, String note,
                              Instant createdAt) {
}
