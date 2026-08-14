package com.robsartin.setlistscout.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ArtistNameMatcherTest {

    private static final String OWNER = "rob@example.com";

    private ArtistRepository artistRepository;
    private ArtistNameMatcher matcher;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        matcher = new ArtistNameMatcher(artistRepository);
    }

    // The view mock must be fully built (mock() + all when(...).thenReturn(...) calls completed)
    // BEFORE the caller's own when(...).thenReturn(...) begins -- calling this inline as an argument
    // to another unfinished when(...).thenReturn(...) throws Mockito's UnfinishedStubbingException,
    // same gotcha RelationDiscoveredListenerTest#artistWithId documents.
    private static ArtistNameStatusView view(Long id, String name, ArtistStatus status) {
        ArtistNameStatusView v = mock(ArtistNameStatusView.class);
        when(v.getId()).thenReturn(id);
        when(v.getName()).thenReturn(name);
        when(v.getStatus()).thenReturn(status);
        return v;
    }

    @Test
    @DisplayName("finds an existing artist whose name differs only by case")
    void findsCaseVariant() {
        ArtistNameStatusView existing = view(1L, "Charlie Parker's Re-Boppers", ArtistStatus.REJECTED);
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of(existing));

        Optional<ArtistNameStatusView> found = matcher.findExistingMatch(OWNER, "Charlie Parker's Re-boppers");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("finds an existing artist whose name differs by en-dash vs hyphen")
    void findsPunctuationVariant() {
        ArtistNameStatusView existing = view(2L, "Only Murders In The Building - Cast", ArtistStatus.REJECTED);
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of(existing));

        Optional<ArtistNameStatusView> found =
                matcher.findExistingMatch(OWNER, "Only Murders in the Building – Cast");

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("finds a match regardless of the existing row's status")
    void findsMatchRegardlessOfStatus() {
        ArtistNameStatusView existing = view(3L, "Wilco", ArtistStatus.APPROVED);
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of(existing));

        assertThat(matcher.findExistingMatch(OWNER, "wilco")).isPresent();
    }

    @Test
    @DisplayName("two genuinely different names do not match")
    void distinctNamesDoNotMatch() {
        ArtistNameStatusView existing = view(4L, "Radiohead", ArtistStatus.APPROVED);
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of(existing));

        assertThat(matcher.findExistingMatch(OWNER, "Radioheads")).isEmpty();
    }

    @Test
    @DisplayName("returns empty when the owner has no artists at all")
    void emptyWhenNoArtists() {
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of());

        assertThat(matcher.findExistingMatch(OWNER, "Wilco")).isEmpty();
    }
}
