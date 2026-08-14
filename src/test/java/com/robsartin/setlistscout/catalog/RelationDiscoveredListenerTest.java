package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.RelationDiscovered;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RelationDiscoveredListenerTest {

    private static final String OWNER = "rob@example.com";
    private static final Long FROM_ARTIST_ID = 7L;

    private ArtistRepository artistRepository;
    private ArtistEdgeRepository artistEdgeRepository;
    private RelationDiscoveredListener listener;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        artistEdgeRepository = mock(ArtistEdgeRepository.class);
        listener = new RelationDiscoveredListener(artistRepository, artistEdgeRepository);
    }

    private static RelationDiscovered relation(String toArtistName) {
        return new RelationDiscovered(OWNER, FROM_ARTIST_ID, "Dawes", toArtistName,
                ArtistSource.MEMBER_EXPANSION.name(), "musicbrainz", "member/lineup relation of Dawes");
    }

    private static Artist artistWithId(Long id) {
        Artist artist = mock(Artist.class);
        when(artist.getId()).thenReturn(id);
        return artist;
    }

    private void verifyNothingPersisted() {
        verify(artistRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
        verify(artistEdgeRepository, never())
                .insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should skip a blank to-artist name -- no node, no edge")
    void shouldSkipBlankName() {
        listener.on(relation("  "));

        verifyNothingPersisted();
    }

    @Test
    @DisplayName("should skip a null to-artist name -- no node, no edge")
    void shouldSkipNullName() {
        listener.on(relation(null));

        verifyNothingPersisted();
    }

    @Test
    @DisplayName("should skip a prose/refusal response from an LLM expansion source")
    void shouldSkipProseRefusalResponse() {
        listener.on(relation("I don't know of any well-known tribute or cover bands specifically dedicated to "
                + "Brandi Carlile's music."));

        verifyNothingPersisted();
    }

    @Test
    @DisplayName("should skip a name prefixed with #")
    void shouldSkipHashPrefixedName() {
        listener.on(relation("#1 Fan Club"));

        verifyNothingPersisted();
    }

    @Test
    @DisplayName("should skip a name longer than 60 characters")
    void shouldSkipOverlongName() {
        listener.on(relation("A".repeat(61)));

        verifyNothingPersisted();
    }

    @Test
    @DisplayName("should skip a name with more than 8 words")
    void shouldSkipTooManyWordsName() {
        listener.on(relation("one two three four five six seven eight nine"));

        verifyNothingPersisted();
    }

    @Test
    @DisplayName("should skip a relation with an unrecognized type -- no node, no edge")
    void shouldSkipUnknownType() {
        RelationDiscovered event = new RelationDiscovered(OWNER, FROM_ARTIST_ID, "Dawes", "Taylor Goldsmith",
                "NOT_A_REAL_TYPE", "musicbrainz", "note");

        listener.on(event);

        verifyNothingPersisted();
    }

    @Test
    @DisplayName("should upsert legitimate punctuated band names, not just plain ones")
    void shouldUpsertPunctuatedArtistNames() {
        // The Artist mock must be built BEFORE the when(...).thenReturn(...) call, not inline as
        // its argument: mock(Artist.class) + when(artist.getId())...thenReturn(...) inside
        // artistWithId() is itself a stubbing call, and Mockito can't have a second when() start
        // while the outer when(...).thenReturn(...) here hasn't completed yet (its argument is
        // still being evaluated) -- that ordering throws UnfinishedStubbingException.
        Artist toArtist = artistWithId(1L);
        when(artistRepository.findByOwnerAndName(any(), any())).thenReturn(Optional.of(toArtist));

        listener.on(relation("Panic! at the Disco"));
        listener.on(relation("St. Vincent"));

        ArgumentCaptor<String> nameCaptor = ArgumentCaptor.forClass(String.class);
        verify(artistRepository, times(2)).insertIfAbsent(
                any(), nameCaptor.capture(), any(), any(), any(), any(), any());
        assertThat(nameCaptor.getAllValues())
                .containsExactlyInAnyOrder("Panic! at the Disco", "St. Vincent");
    }

    @Test
    @DisplayName("should upsert the to-artist node as PENDING_REVIEW with the mapped type, owner, "
            + "discoveredVia (from-artist name), and note")
    void shouldUpsertToArtistNode() {
        Artist toArtist = artistWithId(42L);
        when(artistRepository.findByOwnerAndName(OWNER, "Taylor Goldsmith"))
                .thenReturn(Optional.of(toArtist));
        Instant before = Instant.now();

        listener.on(relation("Taylor Goldsmith"));

        ArgumentCaptor<Instant> createdAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(artistRepository).insertIfAbsent(eq(OWNER), eq("Taylor Goldsmith"),
                eq(ArtistSource.MEMBER_EXPANSION.name()), eq(ArtistStatus.PENDING_REVIEW.name()),
                eq("Dawes"), eq("member/lineup relation of Dawes"), createdAtCaptor.capture());
        assertThat(createdAtCaptor.getValue()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("should resolve the to-artist id and upsert the edge from the base artist to it")
    void shouldUpsertEdge() {
        Artist toArtist = artistWithId(42L);
        when(artistRepository.findByOwnerAndName(OWNER, "Taylor Goldsmith"))
                .thenReturn(Optional.of(toArtist));
        Instant before = Instant.now();

        listener.on(relation("Taylor Goldsmith"));

        ArgumentCaptor<Instant> createdAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(artistEdgeRepository).insertIfAbsent(eq(OWNER), eq(FROM_ARTIST_ID), eq(42L),
                eq(ArtistSource.MEMBER_EXPANSION.name()), eq("musicbrainz"),
                eq("member/lineup relation of Dawes"), isNull(), createdAtCaptor.capture());
        assertThat(createdAtCaptor.getValue()).isNotNull().isAfterOrEqualTo(before);
    }

    @Test
    @DisplayName("should still attempt the node/edge upsert against an already-known to-artist "
            + "(no existsBy short-circuit) -- this is the corroboration fix: a second source for an "
            + "already-known relationship must still get its own edge, not be silently dropped")
    void shouldNotShortCircuitOnAlreadyKnownArtist() {
        Artist toArtist = artistWithId(42L);
        when(artistRepository.findByOwnerAndName(OWNER, "Taylor Goldsmith"))
                .thenReturn(Optional.of(toArtist));

        listener.on(relation("Taylor Goldsmith"));

        verify(artistRepository).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
        verify(artistEdgeRepository).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("should skip the edge write (but still attempt the node upsert) when the to-artist "
            + "id can't be resolved after insertIfAbsent")
    void shouldSkipEdgeWhenIdUnresolvable() {
        when(artistRepository.findByOwnerAndName(OWNER, "Taylor Goldsmith"))
                .thenReturn(Optional.empty());

        listener.on(relation("Taylor Goldsmith"));

        verify(artistRepository).insertIfAbsent(any(), any(), any(), any(), any(), any(), any());
        verify(artistEdgeRepository, never())
                .insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
