package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.shared.MusicBrainzService;
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

    private static final String OWNER = "rob@example.com";

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
        when(artistRepository.findByOwnerAndStatusIn(OWNER, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)))
                .thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());

        expansionService.expandAll(OWNER);

        verify(musicBrainz).findRelatedArtists("Dawes");
        verify(discogs).findRelatedArtists("Dawes");
        verify(lastFm).findSimilarArtists("Dawes", 8);
        verify(similarArtistLlm).findSimilarArtists("Dawes", 8);
    }

    @Test
    @DisplayName("should save a new member-relation artist with the MEMBER_EXPANSION source")
    void shouldSaveNewMemberRelation() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists("Dawes")).thenReturn(List.of("Taylor Goldsmith"));
        when(discogs.findRelatedArtists("Dawes")).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Taylor Goldsmith")).thenReturn(false);

        expansionService.expandAll(OWNER);

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(captor.capture());
        Artist saved = captor.getValue();
        assertThat(saved.getName()).isEqualTo("Taylor Goldsmith");
        assertThat(saved.getOwner()).isEqualTo(OWNER);
        assertThat(saved.getSource()).isEqualTo(ArtistSource.MEMBER_EXPANSION);
        assertThat(saved.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        assertThat(saved.getDiscoveredVia()).isEqualTo("Dawes");
        assertThat(saved.getNote()).isEqualTo("member/lineup relation of Dawes");
    }

    @Test
    @DisplayName("should not save a member relation that is already tracked")
    void shouldSkipExistingMemberRelation() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists("Dawes")).thenReturn(List.of("Taylor Goldsmith"));
        when(discogs.findRelatedArtists("Dawes")).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Taylor Goldsmith")).thenReturn(true);

        expansionService.expandAll(OWNER);

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip blank names returned by an expansion source")
    void shouldSkipBlankNames() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists("Dawes")).thenReturn(List.of("", "  "));
        when(discogs.findRelatedArtists("Dawes")).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());

        expansionService.expandAll(OWNER);

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should note a similar artist found by both sources as confirmed")
    void shouldNoteConfirmedByBothSources() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        when(similarArtistLlm.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        when(artistRepository.existsByOwnerAndNameIgnoreCase(any(), any())).thenReturn(false);

        expansionService.expandAll(OWNER);

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(captor.capture());
        Artist saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(ArtistSource.SIMILAR_EXPANSION);
        assertThat(saved.getNote()).isEqualTo("similar to Dawes (confirmed by Last.fm + LLM)");
    }

    @Test
    @DisplayName("should note a similar artist found by only one source as single-source")
    void shouldNoteSingleSourceMatch() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists("Dawes", 8)).thenReturn(List.of("Nickel Creek"));
        when(similarArtistLlm.findSimilarArtists("Dawes", 8)).thenReturn(List.of());
        when(artistRepository.existsByOwnerAndNameIgnoreCase(any(), any())).thenReturn(false);

        expansionService.expandAll(OWNER);

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(captor.capture());
        assertThat(captor.getValue().getNote()).isEqualTo("similar to Dawes (single-source match)");
    }

    @Test
    @DisplayName("should save a tribute act for a SEED base with the TRIBUTE_EXPANSION source")
    void shouldSaveTributeForSeed() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Iron Maiden")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(tributeLlm.findTributeBands("Iron Maiden", 5)).thenReturn(List.of("The Iron Maidens"));
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "The Iron Maidens")).thenReturn(false);

        expansionService.expandAll(OWNER);

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
    @DisplayName("should skip a prose/refusal response from an LLM expansion source")
    void shouldSkipProseRefusalResponse() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any()))
                .thenReturn(List.of(seedArtist("Brandi Carlile")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(tributeLlm.findTributeBands("Brandi Carlile", 5)).thenReturn(List.of(
                "I don't know of any well-known tribute or cover bands specifically dedicated to "
                        + "Brandi Carlile's music."));

        expansionService.expandAll(OWNER);

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should save legitimate punctuated band names, not just plain ones")
    void shouldSavePunctuatedArtistNames() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any()))
                .thenReturn(List.of(seedArtist("Talking Heads")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists("Talking Heads", 8))
                .thenReturn(List.of("Panic! at the Disco", "St. Vincent"));
        when(artistRepository.existsByOwnerAndNameIgnoreCase(any(), any())).thenReturn(false);

        expansionService.expandAll(OWNER);

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Artist::getName)
                .containsExactlyInAnyOrder("Panic! at the Disco", "St. Vincent");
    }

    @Test
    @DisplayName("should still save a plausible short tribute name alongside a rejected refusal")
    void shouldSaveNormalNameEvenWhenAnotherIsRefusal() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any()))
                .thenReturn(List.of(seedArtist("Iron Maiden")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(tributeLlm.findTributeBands("Iron Maiden", 5)).thenReturn(List.of(
                "The Iron Maidens",
                "I don't know of any well-known tribute or cover bands specifically dedicated to "
                        + "Iron Maiden's music."));
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "The Iron Maidens")).thenReturn(false);

        expansionService.expandAll(OWNER);

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository, org.mockito.Mockito.times(1)).save(captor.capture());
        assertThat(captor.getValue().getName()).isEqualTo("The Iron Maidens");
    }

    @Test
    @DisplayName("should NOT run tribute expansion for an APPROVED (non-seed) base")
    void shouldSkipTributeForApproved() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(approvedArtist("Nickel Creek")));
        when(musicBrainz.findRelatedArtists(any())).thenReturn(List.of());
        when(discogs.findRelatedArtists(any())).thenReturn(List.of());
        when(lastFm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());
        when(similarArtistLlm.findSimilarArtists(any(), eq(8))).thenReturn(List.of());

        expansionService.expandAll(OWNER);

        verify(tributeLlm, never()).findTributeBands(any(), anyInt());
    }
}
