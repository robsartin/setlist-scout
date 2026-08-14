package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;

import java.util.List;

/** A single related-artist source (MusicBrainz, Discogs, Last.fm, LLMs). Query-only: never writes. */
public interface RelationSource {
    /** Stable source key used in logs and (Phase B PR4) the expand_job.source column. */
    String id();

    /** Candidate related-artist names for the given base artist. */
    List<String> related(String artistName);

    /** How candidates from this source should be classified when published as {@code RelationDiscovered}. */
    ArtistSource classification();

    /** Human-readable explanation of why a candidate from this source was discovered, for the given base artist. */
    String note(String baseArtist);

    /** Whether this source should be expanded for an artist in the given status. Default: always. */
    default boolean appliesTo(ArtistStatus status) {
        return true;
    }
}
