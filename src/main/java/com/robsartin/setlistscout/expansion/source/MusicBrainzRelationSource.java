package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Member/lineup relations from MusicBrainz behind the {@link RelationSource} port. */
@Component
public class MusicBrainzRelationSource implements RelationSource {

    private final MusicBrainzService musicBrainz;

    public MusicBrainzRelationSource(MusicBrainzService musicBrainz) {
        this.musicBrainz = musicBrainz;
    }

    @Override
    public String id() {
        return "musicbrainz";
    }

    @Override
    public List<String> related(String artistName) {
        return musicBrainz.findRelatedArtists(artistName);
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
