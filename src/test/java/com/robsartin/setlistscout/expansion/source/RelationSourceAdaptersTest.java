package com.robsartin.setlistscout.expansion.source;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.expansion.DiscogsService;
import com.robsartin.setlistscout.expansion.LastFmService;
import com.robsartin.setlistscout.expansion.SimilarArtistLlmService;
import com.robsartin.setlistscout.expansion.TributeLlmService;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RelationSourceAdaptersTest {

    @Test
    void musicBrainzAdapterDelegatesAndIsIdMusicbrainz() {
        MusicBrainzService mb = mock(MusicBrainzService.class);
        when(mb.findRelatedArtists("Dawes")).thenReturn(List.of("Taylor Goldsmith"));
        MusicBrainzRelationSource s = new MusicBrainzRelationSource(mb);
        assertThat(s.id()).isEqualTo("musicbrainz");
        assertThat(s.related("Dawes")).containsExactly("Taylor Goldsmith");
        assertThat(s.classification()).isEqualTo(ArtistSource.MEMBER_EXPANSION);
        assertThat(s.note("Dawes")).isEqualTo("member/lineup relation of Dawes");
    }

    @Test
    void discogsAdapterDelegatesAndIsIdDiscogs() {
        DiscogsService d = mock(DiscogsService.class);
        when(d.findRelatedArtists("Dawes")).thenReturn(List.of("Middle Brother"));
        DiscogsRelationSource s = new DiscogsRelationSource(d);
        assertThat(s.id()).isEqualTo("discogs");
        assertThat(s.related("Dawes")).containsExactly("Middle Brother");
        assertThat(s.classification()).isEqualTo(ArtistSource.MEMBER_EXPANSION);
        assertThat(s.note("Dawes")).isEqualTo("member/lineup relation of Dawes");
    }

    @Test
    void lastFmAdapterDelegatesWithLimit8AndIsIdLastfm() {
        LastFmService lf = mock(LastFmService.class);
        when(lf.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        LastFmSimilarSource s = new LastFmSimilarSource(lf);
        assertThat(s.id()).isEqualTo("lastfm");
        assertThat(s.related("Dawes")).containsExactly("Nickel Creek");
        assertThat(s.classification()).isEqualTo(ArtistSource.SIMILAR_EXPANSION);
        assertThat(s.note("Dawes")).isEqualTo("similar to Dawes (via Last.fm)");
    }

    @Test
    void similarLlmAdapterDelegatesWithLimit8AndIsIdSimilarLlm() {
        SimilarArtistLlmService llm = mock(SimilarArtistLlmService.class);
        when(llm.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        SimilarLlmSource s = new SimilarLlmSource(llm);
        assertThat(s.id()).isEqualTo("similar-llm");
        assertThat(s.related("Dawes")).containsExactly("Nickel Creek");
        assertThat(s.classification()).isEqualTo(ArtistSource.SIMILAR_EXPANSION);
        assertThat(s.note("Dawes")).isEqualTo("similar to Dawes (via LLM)");
    }

    @Test
    void tributeLlmAdapterDelegatesWithLimit5AndIsIdTributeLlm() {
        TributeLlmService t = mock(TributeLlmService.class);
        when(t.findTributeBands("Iron Maiden", 5)).thenReturn(List.of("The Iron Maidens"));
        TributeLlmSource s = new TributeLlmSource(t);
        assertThat(s.id()).isEqualTo("tribute-llm");
        assertThat(s.related("Iron Maiden")).containsExactly("The Iron Maidens");
        assertThat(s.classification()).isEqualTo(ArtistSource.TRIBUTE_EXPANSION);
        assertThat(s.note("Dawes")).isEqualTo("tribute/cover act for Dawes");
    }
}
