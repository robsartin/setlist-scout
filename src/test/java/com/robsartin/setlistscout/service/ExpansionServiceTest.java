package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpansionServiceTest {

    @Mock private ArtistRepository artistRepository;
    @Mock private MusicBrainzService musicBrainz;
    @Mock private DiscogsService discogs;
    @Mock private LastFmService lastFm;
    @Mock private SimilarArtistLlmService similarArtistLlm;
    @Mock private TributeLlmService tributeLlm;

    private ExpansionService expansionService;

    @BeforeEach
    void setUp() {
        expansionService = new ExpansionService(
                artistRepository, musicBrainz, discogs, lastFm, similarArtistLlm, tributeLlm);
    }

    private static Artist seedArtist(String name) {
        return new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
    }

    private static Artist approvedArtist(String name) {
        return new Artist(name, ArtistSource.SIMILAR_EXPANSION, ArtistStatus.APPROVED, "x", "x");
    }

    @Test
    @DisplayName("should only expand SEED and APPROVED artists")
    void shouldOnlyExpandActiveArtists() {
        when(artistRepository.findByStatusIn(List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)))
                .thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());

        expansionService.expandAll();

        verify(musicBrainz).findRelatedArtists("Dawes");
        verify(discogs).findRelatedArtists("Dawes");
        verify(lastFm).findSimilarArtists("Dawes", 8);
        verify(similarArtistLlm).findSimilarArtists("Dawes", 8);
    }

    @Test
    @DisplayName("should save a new member-relation artist with the MEMBER_EXPANSION source")
    void shouldSaveNewMemberRelation() {
        when(artistRepository.findByStatusIn(any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists("Dawes")).thenReturn(List.of("Taylor Goldsmith"));
        when(discogs.findRelatedArtists("Dawes")).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(artistRepository.existsByNameIgnoreCase("Taylor Goldsmith")).thenReturn(false);

        expansionService.expandAll();

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(captor.capture());
        Artist saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("Taylor Goldsmith");
        assertThat(saved.getSource()).isEqualTo(ArtistSource.MEMBER_EXPANSION);
        assertThat(saved.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(saved.getDiscoveredVia()).isEqualTo("Dawes");
        assertThat(saved.getNote()).isEqualTo("member/lineup relation of Dawes");
    }

    @Test
    @DisplayName("should not save a member relation that is already tracked")
    void shouldSkipExistingMemberRelation() {
        when(artistRepository.findByStatusIn(any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists("Dawes")).thenReturn(List.of("Taylor Goldsmith"));
        when(discogs.findRelatedArtists("Dawes")).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(artistRepository.existsByNameIgnoreCase("Taylor Goldsmith")).thenReturn(true);

        expansionService.expandAll();

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip blank names returned by an expansion source")
    void shouldSkipBlankNames() {
        when(artistRepository.findByStatusIn(any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists("Dawes")).thenReturn(List.of("", "  "));
        when(discogs.findRelatedArtists("Dawes")).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());

        expansionService.expandAll();

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should note a similar artist found by both sources as confirmed")
    void shouldNoteConfirmedByBothSources() {
        when(artistRepository.findByStatusIn(any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        when(similarArtistLlm.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        when(artistRepository.existsByNameIgnoreCase(any())).thenReturn(false);

        expansionService.expandAll();

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(captor.capture());
        Artist saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(ArtistSource.SIMILAR_EXPANSION);
        assertThat(saved.getNote()).isEqualTo("similar to Dawes (confirmed by Last.fm + LLM)");
    }

    @Test
    @DisplayName("should note a similar artist found by only one source as single-source")
    void shouldNoteSingleSourceMatch() {
        when(artistRepository.findByStatusIn(any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        when(similarArtistLlm.findSimilarArtists("Dawes", 8)).thenReturn(List.of());
        when(artistRepository.existsByNameIgnoreCase(any())).thenReturn(false);

        expansionService.expandAll();

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(captor.capture());
        assertThat(captor.getValue().getNote()).isEqualTo("similar to Dawes (single-source match)");
    }

    @Test
    @DisplayName("should save a tribute act for a SEED base with the TRIBUTE_EXPANSION source")
    void shouldSaveTributeForSeed() {
        when(artistRepository.findByStatusIn(any())).thenReturn(List.of(seedArtist("Iron Maiden")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(tributeLlm.findTributeBands("Iron Maiden", 5)).thenReturn(List.of("The Iron Maidens"));
        when(artistRepository.existsByNameIgnoreCase("The Iron Maidens")).thenReturn(false);

        expansionService.expandAll();

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(captor.capture());
        Artist saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("The Iron Maidens");
        assertThat(saved.getSource()).isEqualTo(ArtistSource.TRIBUTE_EXPANSION);
        assertThat(saved.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(saved.getDiscoveredVia()).isEqualTo("Iron Maiden");
        assertThat(saved.getNote()).isEqualTo("tribute/cover act for Iron Maiden");
    }

    @Test
    @DisplayName("should NOT run tribute expansion for an APPROVED (non-seed) base")
    void shouldSkipTributeForApproved() {
        when(artistRepository.findByStatusIn(any())).thenReturn(List.of(approvedArtist("Nickel Creek")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());

        expansionService.expandAll();

        verify(tributeLlm, never()).findTributeBands(any(), anyInt());
    }
}
