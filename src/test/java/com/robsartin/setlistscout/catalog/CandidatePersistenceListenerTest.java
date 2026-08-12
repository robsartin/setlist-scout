package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.CandidateDiscovered;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CandidatePersistenceListenerTest {

    private static final String OWNER = "rob@example.com";

    private ArtistRepository artistRepository;
    private CandidatePersistenceListener listener;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        listener = new CandidatePersistenceListener(artistRepository);
    }

    private static CandidateDiscovered candidate(String name) {
        return new CandidateDiscovered(OWNER, name, ArtistSource.MEMBER_EXPANSION.name(), "Dawes",
                "member/lineup relation of Dawes");
    }

    @Test
    @DisplayName("should skip a blank name")
    void shouldSkipBlankName() {
        listener.on(candidate("  "));

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip a null name")
    void shouldSkipNullName() {
        listener.on(candidate(null));

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip a prose/refusal response from an LLM expansion source")
    void shouldSkipProseRefusalResponse() {
        listener.on(candidate("I don't know of any well-known tribute or cover bands specifically dedicated to "
                + "Brandi Carlile's music."));

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip a name prefixed with #")
    void shouldSkipHashPrefixedName() {
        listener.on(candidate("#1 Fan Club"));

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip a name longer than 60 characters")
    void shouldSkipOverlongName() {
        listener.on(candidate("A".repeat(61)));

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should skip a name with more than 8 words")
    void shouldSkipTooManyWordsName() {
        listener.on(candidate("one two three four five six seven eight nine"));

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should save legitimate punctuated band names, not just plain ones")
    void shouldSavePunctuatedArtistNames() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(any(), any())).thenReturn(false);

        listener.on(candidate("Panic! at the Disco"));
        listener.on(candidate("St. Vincent"));

        ArgumentCaptor<Artist> captor = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository, org.mockito.Mockito.times(2)).save(captor.capture());
        assertThat(captor.getAllValues())
                .extracting(Artist::getName)
                .containsExactlyInAnyOrder("Panic! at the Disco", "St. Vincent");
    }

    @Test
    @DisplayName("should skip a candidate already tracked for this owner")
    void shouldSkipExistingCandidate() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Taylor Goldsmith")).thenReturn(true);

        listener.on(candidate("Taylor Goldsmith"));

        verify(artistRepository, never()).save(any());
    }

    @Test
    @DisplayName("should save a new candidate as PENDING_REVIEW with the mapped source, owner, discoveredVia, and note")
    void shouldSaveNewCandidate() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Taylor Goldsmith")).thenReturn(false);

        listener.on(candidate("Taylor Goldsmith"));

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
}
