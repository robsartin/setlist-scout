package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.DiscogsRelationSource;
import com.robsartin.setlistscout.expansion.source.LastFmSimilarSource;
import com.robsartin.setlistscout.expansion.source.MusicBrainzRelationSource;
import com.robsartin.setlistscout.expansion.source.SimilarLlmSource;
import com.robsartin.setlistscout.expansion.source.TributeLlmSource;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExpansionServiceTest {

    @Mock private ArtistRepository artistRepository;
    @Mock private MusicBrainzRelationSource musicBrainzSource;
    @Mock private DiscogsRelationSource discogsSource;
    @Mock private LastFmSimilarSource lastFmSource;
    @Mock private SimilarLlmSource similarLlmSource;
    @Mock private TributeLlmSource tributeSource;

    private ExpansionService expansionService;

    private static final String OWNER = "rob@example.com";

    @BeforeEach
    void setUp() {
        expansionService = new ExpansionService(
                artistRepository, musicBrainzSource, discogsSource, lastFmSource, similarLlmSource, tributeSource);
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
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());

        expansionService.expandAll(OWNER);

        verify(musicBrainzSource).related("Dawes");
        verify(discogsSource).related("Dawes");
        verify(lastFmSource).related("Dawes");
        verify(similarLlmSource).related("Dawes");
    }

    @Test
    @DisplayName("should save a new member-relation artist with the MEMBER_EXPANSION source")
    void shouldSaveNewMemberRelation() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainzSource.related("Dawes")).thenReturn(List.of("Taylor Goldsmith"));
        when(discogsSource.related("Dawes")).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());
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
        when(musicBrainzSource.related("Dawes")).thenReturn(List.of("Taylor Goldsmith"));
        when(discogsSource.related("Dawes")).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Taylor Goldsmith")).thenReturn(true);

        expansionService.expandAll(OWNER);

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip blank names returned by an expansion source")
    void shouldSkipBlankNames() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainzSource.related("Dawes")).thenReturn(List.of("", "  "));
        when(discogsSource.related("Dawes")).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());

        expansionService.expandAll(OWNER);

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should note a similar artist found by both sources as confirmed")
    void shouldNoteConfirmedByBothSources() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related("Dawes")).thenReturn(List.of("Nickel Creek"));
        when(similarLlmSource.related("Dawes")).thenReturn(List.of("Nickel Creek"));
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
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related("Dawes")).thenReturn(List.of("Nickel Creek"));
        when(similarLlmSource.related("Dawes")).thenReturn(List.of());
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
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());
        when(tributeSource.related("Iron Maiden")).thenReturn(List.of("The Iron Maidens"));
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
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());
        when(tributeSource.related("Brandi Carlile")).thenReturn(List.of(
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
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related("Talking Heads"))
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
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());
        when(tributeSource.related("Iron Maiden")).thenReturn(List.of(
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
    @DisplayName("should isolate a failing dimension so other dimensions and other artists still process")
    void shouldIsolateFailingDimension() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any()))
                .thenReturn(List.of(seedArtist("First"), seedArtist("Second")));

        // "First" artist: member-relation dimension blows up on the Discogs call.
        when(musicBrainzSource.related("First")).thenReturn(List.of());
        when(discogsSource.related("First")).thenThrow(new RuntimeException("boom"));
        when(lastFmSource.related("First")).thenReturn(List.of("SimilarFirst"));
        when(similarLlmSource.related("First")).thenReturn(List.of());
        when(tributeSource.related("First")).thenReturn(List.of("TributeFirst"));

        // "Second" artist: everything behaves normally.
        when(musicBrainzSource.related("Second")).thenReturn(List.of("MemberSecond"));
        when(discogsSource.related("Second")).thenReturn(List.of());
        when(lastFmSource.related("Second")).thenReturn(List.of());
        when(similarLlmSource.related("Second")).thenReturn(List.of());
        when(tributeSource.related("Second")).thenReturn(List.of("TributeSecond"));

        when(artistRepository.existsByOwnerAndNameIgnoreCase(eq(OWNER), any())).thenReturn(false);

        expansionService.expandAll(OWNER);

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository, org.mockito.Mockito.times(4)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Artist::getName)
                .containsExactlyInAnyOrder("SimilarFirst", "TributeFirst", "MemberSecond", "TributeSecond");
    }

    @Test
    @DisplayName("should NOT run tribute expansion for an APPROVED (non-seed) base")
    void shouldSkipTributeForApproved() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(approvedArtist("Nickel Creek")));
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());

        expansionService.expandAll(OWNER);

        verify(tributeSource, never()).related(any());
    }
}
