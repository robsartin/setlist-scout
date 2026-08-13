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
import com.robsartin.setlistscout.shared.events.CandidateDiscovered;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Mock private ApplicationEventPublisher publisher;
    @Mock private TransactionTemplate transactionTemplate;

    private ExpansionService expansionService;

    private static final String OWNER = "rob@example.com";

    @BeforeEach
    void setUp() {
        // Run the "transaction" synchronously and inline, same as the real TransactionTemplate
        // would for a caller with no active transaction -- lets the existing publisher-focused
        // assertions below keep working unchanged.
        // lenient(): not every test publishes an event, so not every test exercises this stub.
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        expansionService = new ExpansionService(
                artistRepository, musicBrainzSource, discogsSource, lastFmSource, similarLlmSource, tributeSource,
                publisher, transactionTemplate);
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
    @DisplayName("should publish a CandidateDiscovered event for a new member-relation artist")
    void shouldPublishMemberRelation() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainzSource.related("Dawes")).thenReturn(List.of("Taylor Goldsmith"));
        when(discogsSource.related("Dawes")).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());

        expansionService.expandAll(OWNER);

        ArgumentCaptor<CandidateDiscovered> captor = ArgumentCaptor.forClass(CandidateDiscovered.class);
        verify(publisher).publishEvent(captor.capture());
        CandidateDiscovered published = captor.getValue();
        assertThat(published.owner()).isEqualTo(OWNER);
        assertThat(published.name()).isEqualTo("Taylor Goldsmith");
        assertThat(published.sourceType()).isEqualTo(ArtistSource.MEMBER_EXPANSION.name());
        assertThat(published.discoveredVia()).isEqualTo("Dawes");
        assertThat(published.note()).isEqualTo("member/lineup relation of Dawes");
    }

    @Test
    @DisplayName("should note a similar artist found by both sources as confirmed")
    void shouldNoteConfirmedByBothSources() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related("Dawes")).thenReturn(List.of("Nickel Creek"));
        when(similarLlmSource.related("Dawes")).thenReturn(List.of("Nickel Creek"));

        expansionService.expandAll(OWNER);

        ArgumentCaptor<CandidateDiscovered> captor = ArgumentCaptor.forClass(CandidateDiscovered.class);
        verify(publisher).publishEvent(captor.capture());
        CandidateDiscovered published = captor.getValue();
        assertThat(published.sourceType()).isEqualTo(ArtistSource.SIMILAR_EXPANSION.name());
        assertThat(published.note()).isEqualTo("similar to Dawes (confirmed by Last.fm + LLM)");
    }

    @Test
    @DisplayName("should note a similar artist found by only one source as single-source")
    void shouldNoteSingleSourceMatch() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Dawes")));
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related("Dawes")).thenReturn(List.of("Nickel Creek"));
        when(similarLlmSource.related("Dawes")).thenReturn(List.of());

        expansionService.expandAll(OWNER);

        ArgumentCaptor<CandidateDiscovered> captor = ArgumentCaptor.forClass(CandidateDiscovered.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue().note()).isEqualTo("similar to Dawes (single-source match)");
    }

    @Test
    @DisplayName("should publish a tribute act for a SEED base with the TRIBUTE_EXPANSION source")
    void shouldPublishTributeForSeed() {
        when(artistRepository.findByOwnerAndStatusIn(any(), any())).thenReturn(List.of(seedArtist("Iron Maiden")));
        when(musicBrainzSource.related(any())).thenReturn(List.of());
        when(discogsSource.related(any())).thenReturn(List.of());
        when(lastFmSource.related(any())).thenReturn(List.of());
        when(similarLlmSource.related(any())).thenReturn(List.of());
        when(tributeSource.related("Iron Maiden")).thenReturn(List.of("The Iron Maidens"));

        expansionService.expandAll(OWNER);

        ArgumentCaptor<CandidateDiscovered> captor = ArgumentCaptor.forClass(CandidateDiscovered.class);
        verify(publisher).publishEvent(captor.capture());
        CandidateDiscovered published = captor.getValue();
        assertThat(published.name()).isEqualTo("The Iron Maidens");
        assertThat(published.sourceType()).isEqualTo(ArtistSource.TRIBUTE_EXPANSION.name());
        assertThat(published.discoveredVia()).isEqualTo("Iron Maiden");
        assertThat(published.note()).isEqualTo("tribute/cover act for Iron Maiden");
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

        expansionService.expandAll(OWNER);

        ArgumentCaptor<CandidateDiscovered> captor = ArgumentCaptor.forClass(CandidateDiscovered.class);
        verify(publisher, times(4)).publishEvent(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(CandidateDiscovered::name)
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
