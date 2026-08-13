package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.catalog.ArtistSource;

import java.util.List;

/** A single related-artist source (MusicBrainz, Discogs, Last.fm, LLMs). Query-only: never writes. */
public interface RelationSource {
    /** Stable source key used in logs and (Phase B PR4) the expand_job.source column. */
    String id();

    /** Candidate related-artist names for the given base artist. */
    List<String> related(String artistName);

    /** How candidates from this source should be classified when published as {@code CandidateDiscovered}. */
    ArtistSource classification();

    /** Human-readable explanation of why a candidate from this source was discovered, for the given base artist. */
    String note(String baseArtist);
}
