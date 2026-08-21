package com.robsartin.setlistscout.catalog;

public enum ArtistSource {
    SEED_LIST,           // hand-entered starting list
    MEMBER_EXPANSION,    // found via MusicBrainz/Discogs lineup relationships
    SIMILAR_EXPANSION,   // found via Last.fm / LLM similarity
    TRIBUTE_EXPANSION,   // found via LLM tribute/cover-band lookup (seed bands only)
    VENUE_EXPANSION      // seen performing at a followed venue (#206)
}
