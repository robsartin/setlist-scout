package com.robsartin.setlistscout.catalog;

/**
 * Lightweight (id, name, status) projection of one owner's artists, for {@link ArtistNameMatcher}'s
 * in-app normalized-name scan (issue #118) -- avoids pulling every column (note, discoveredVia,
 * etc.) when all that's needed is enough to detect a near-duplicate name across every status,
 * including REJECTED.
 */
public interface ArtistNameStatusView {
    Long getId();
    String getName();
    ArtistStatus getStatus();
}
