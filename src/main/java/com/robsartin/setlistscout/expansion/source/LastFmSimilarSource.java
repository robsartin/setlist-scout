package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.expansion.LastFmService;
import org.springframework.stereotype.Component;

import java.util.List;

/** Similar artists from Last.fm (limit 8) behind the {@link RelationSource} port. */
@Component
public class LastFmSimilarSource implements RelationSource {

    private final LastFmService lastFm;

    public LastFmSimilarSource(LastFmService lastFm) {
        this.lastFm = lastFm;
    }

    @Override
    public String id() {
        return "lastfm";
    }

    @Override
    public List<String> related(String artistName) {
        return lastFm.findSimilarArtists(artistName, 8);
    }
}
