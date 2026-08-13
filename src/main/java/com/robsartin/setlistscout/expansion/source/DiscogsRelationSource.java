package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.expansion.DiscogsService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Related artists from Discogs behind the {@link RelationSource} port. */
@Component
public class DiscogsRelationSource implements RelationSource {

    private final DiscogsService discogs;

    public DiscogsRelationSource(DiscogsService discogs) {
        this.discogs = discogs;
    }

    @Override
    public String id() {
        return "discogs";
    }

    @Override
    public List<String> related(String artistName) {
        return discogs.findRelatedArtists(artistName);
    }

    @Override
    public ArtistSource classification() {
        return ArtistSource.MEMBER_EXPANSION;
    }

    @Override
    public String note(String baseArtist) {
        return "member/lineup relation of " + baseArtist;
    }
}
