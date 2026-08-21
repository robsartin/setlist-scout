package com.robsartin.setlistscout.scan;

/**
 * Projection for one row of {@link ShowRepository#countByOwnerGroupedByVenueSource}: a distinct
 * {@code venue:}-prefixed {@code source} value and how many shows carry it, for one owner
 * (#206 Task 6). Alias names in the query ({@code source}/{@code showCount}) must match these
 * getters. Mirrors {@code catalog.ReachableArtist}'s own projection-interface shape.
 */
public interface VenueSourceCount {
    String getSource();
    long getShowCount();
}
