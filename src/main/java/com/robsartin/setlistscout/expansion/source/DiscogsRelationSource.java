package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.expansion.DiscogsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Related artists from Discogs behind the {@link RelationSource} port. On by default
 * ({@code matchIfMissing = true}) -- {@code setlistscout.sources.discogs=false} opts this source
 * out with zero effect on the other 7 (issue #139).
 */
@Component
@ConditionalOnProperty(name = "setlistscout.sources.discogs", havingValue = "true", matchIfMissing = true)
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
