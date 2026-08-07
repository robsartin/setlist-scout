package com.robsartin.setlistscout.domain;

public enum ArtistSource {
    SEED_LIST,           // hand-entered starting list
    MEMBER_EXPANSION,    // found via MusicBrainz/Discogs lineup relationships
    SIMILAR_EXPANSION    // found via Last.fm / LLM similarity
}
