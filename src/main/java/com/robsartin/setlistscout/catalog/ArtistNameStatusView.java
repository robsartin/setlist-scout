package com.robsartin.setlistscout.catalog;

/**
 * Lightweight (id, name, status) projection of one owner's artist, returned by
 * {@link ArtistRepository#findFirstByOwnerAndNormalizedName} -- the indexed lookup
 * {@link ArtistNameMatcher} uses to detect a near-duplicate name across every status, including
 * REJECTED (issue #118). Avoids pulling every column (note, discoveredVia, etc.) when all that's
 * needed is enough to decide "is this the same artist" and, via {@link #getName()}, to log which
 * existing spelling it matched (see {@code RelationDiscoveredListener#resolveOrCreateToArtist}).
 */
public interface ArtistNameStatusView {
    Long getId();
    String getName();
    ArtistStatus getStatus();
}
