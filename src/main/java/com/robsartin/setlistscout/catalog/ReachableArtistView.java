package com.robsartin.setlistscout.catalog;

/**
 * Display row for one entry of the 2-hop reachable set on the graph-validation page (issue
 * #111): a resolved artist name plus the shortest depth it was reached at.
 */
public record ReachableArtistView(String name, int depth) {
}
