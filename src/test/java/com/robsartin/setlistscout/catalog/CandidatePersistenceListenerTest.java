package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.CandidateDiscovered;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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

    private static void verifyNeverPersisted(ArtistRepository artistRepository) {
        verify(artistRepository, never()).insertIfAbsent(
                any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should skip a blank name")
    void shouldSkipBlankName() {
        listener.on(candidate("  "));

        verifyNeverPersisted(artistRepository);
    }

    @Test
    @DisplayName("should skip a null name")
    void shouldSkipNullName() {
        listener.on(candidate(null));

        verifyNeverPersisted(artistRepository);
    }

    @Test
    @DisplayName("should skip a prose/refusal response from an LLM expansion source")
    void shouldSkipProseRefusalResponse() {
        listener.on(candidate("I don't know of any well-known tribute or cover bands specifically dedicated to "
                + "Brandi Carlile's music."));

        verifyNeverPersisted(artistRepository);
    }

    @Test
    @DisplayName("should skip a name prefixed with #")
    void shouldSkipHashPrefixedName() {
        listener.on(candidate("#1 Fan Club"));

        verifyNeverPersisted(artistRepository);
    }

    @Test
    @DisplayName("should skip a name longer than 60 characters")
    void shouldSkipOverlongName() {
        listener.on(candidate("A".repeat(61)));

        verifyNeverPersisted(artistRepository);
    }

    @Test
    @DisplayName("should skip a name with more than 8 words")
    void shouldSkipTooManyWordsName() {
        listener.on(candidate("one two three four five six seven eight nine"));

        verifyNeverPersisted(artistRepository);
    }

    @Test
    @DisplayName("should save legitimate punctuated band names, not just plain ones")
    void shouldSavePunctuatedArtistNames() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(any(), any())).thenReturn(false);

        listener.on(candidate("Panic! at the Disco"));
        listener.on(candidate("St. Vincent"));

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(artistRepository, times(2)).insertIfAbsent(
                any(), nameCaptor.capture(), any(), any(), any(), any(), any());
        assertThat(nameCaptor.getAllValues())
                .containsExactlyInAnyOrder("Panic! at the Disco", "St. Vincent");
    }

    @Test
    @DisplayName("should skip a candidate already tracked for this owner")
    void shouldSkipExistingCandidate() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Taylor Goldsmith")).thenReturn(true);

        listener.on(candidate("Taylor Goldsmith"));

        verifyNeverPersisted(artistRepository);
    }

    @Test
    @DisplayName("should persist a new candidate as PENDING_REVIEW with the mapped source, owner, discoveredVia, and note")
    void shouldSaveNewCandidate() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Taylor Goldsmith")).thenReturn(false);
        Instant before = Instant.now();

        listener.on(candidate("Taylor Goldsmith"));

        ArgumentCaptor<String> ownerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sourceCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> statusCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> discoveredViaCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> noteCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> createdAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(artistRepository).insertIfAbsent(ownerCaptor.capture(), nameCaptor.capture(),
                sourceCaptor.capture(), statusCaptor.capture(), discoveredViaCaptor.capture(),
                noteCaptor.capture(), createdAtCaptor.capture());

        assertThat(ownerCaptor.getValue()).isEqualTo(OWNER);
        assertThat(nameCaptor.getValue()).isEqualTo("Taylor Goldsmith");
        assertThat(sourceCaptor.getValue()).isEqualTo(ArtistSource.MEMBER_EXPANSION.name());
        assertThat(statusCaptor.getValue()).isEqualTo(ArtistStatus.PENDING_REVIEW.name());
        assertThat(discoveredViaCaptor.getValue()).isEqualTo("Dawes");
        assertThat(noteCaptor.getValue()).isEqualTo("member/lineup relation of Dawes");
        assertThat(createdAtCaptor.getValue()).isNotNull().isAfterOrEqualTo(before);
    }
}
