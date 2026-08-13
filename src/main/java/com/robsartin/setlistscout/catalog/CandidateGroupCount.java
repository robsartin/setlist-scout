package com.robsartin.setlistscout.catalog;

/** Projection for a grouped candidate count: one row per (discoveredVia, source) pair. */
public interface CandidateGroupCount {
    String getVia();
    ArtistSource getSource();
    long getCount();
}
