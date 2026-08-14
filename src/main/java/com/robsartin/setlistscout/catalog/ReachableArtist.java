package com.robsartin.setlistscout.catalog;

/**
 * Projection for one row of {@link ArtistEdgeRepository#reachableWithin}: an artist reachable
 * from a start artist within N hops, and the shortest depth at which it was reached. Alias names
 * in the native query ({@code artistId}/{@code depth}) must match these getters.
 */
public interface ReachableArtist {
    Long getArtistId();
    int getDepth();
}
