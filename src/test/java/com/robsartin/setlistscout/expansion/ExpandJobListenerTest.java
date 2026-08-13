package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpandJobListenerTest {

    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 42L;

    private ExpandJobRepository expandJobRepository;
    private ArtistRepository artistRepository;
    private RelationSource musicBrainz;
    private RelationSource discogs;
    private RelationSource lastFm;
    private RelationSource tributeLlm;
    private ExpandJobListener listener;

    @BeforeEach
    void setUp() {
        expandJobRepository = mock(ExpandJobRepository.class);
        artistRepository = mock(ArtistRepository.class);
        musicBrainz = mock(RelationSource.class);
        discogs = mock(RelationSource.class);
        lastFm = mock(RelationSource.class);
        tributeLlm = mock(RelationSource.class);
        when(musicBrainz.id()).thenReturn("musicbrainz");
        when(musicBrainz.classification()).thenReturn(ArtistSource.MEMBER_EXPANSION);
        when(discogs.id()).thenReturn("discogs");
        when(discogs.classification()).thenReturn(ArtistSource.MEMBER_EXPANSION);
        when(lastFm.id()).thenReturn("lastfm");
        when(lastFm.classification()).thenReturn(ArtistSource.SIMILAR_EXPANSION);
        when(tributeLlm.id()).thenReturn("tribute-llm");
        when(tributeLlm.classification()).thenReturn(ArtistSource.TRIBUTE_EXPANSION);

        listener = new ExpandJobListener(expandJobRepository, List.of(musicBrainz, discogs, lastFm, tributeLlm),
                artistRepository);
    }

    private Artist artistWithStatus(ArtistStatus status) {
        Artist artist = mock(Artist.class);
        when(artist.getStatus()).thenReturn(status);
        return artist;
    }

    @Test
    @DisplayName("SEED artist activation enqueues every source, including tribute-llm")
    void seedArtistEnqueuesAllSources() {
        Artist seedArtist = artistWithStatus(ArtistStatus.SEED);
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(seedArtist));

        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        for (String sourceId : List.of("musicbrainz", "discogs", "lastfm", "tribute-llm")) {
            verify(expandJobRepository).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq(sourceId),
                    argThat(nextDueAt -> nextDueAt.isAfter(Instant.now().minusSeconds(5))
                            && nextDueAt.isBefore(Instant.now().plusSeconds(5))));
        }
    }

    @Test
    @DisplayName("APPROVED artist activation enqueues non-tribute sources but never tribute-llm")
    void approvedArtistSkipsTributeSource() {
        Artist approvedArtist = artistWithStatus(ArtistStatus.APPROVED);
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(approvedArtist));

        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        for (String sourceId : List.of("musicbrainz", "discogs", "lastfm")) {
            verify(expandJobRepository).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq(sourceId), any());
        }
        verify(expandJobRepository, never()).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq("tribute-llm"), any());
    }

    @Test
    @DisplayName("artist-not-found activation skips tribute-llm but still enqueues other sources")
    void artistNotFoundSkipsTributeSource() {
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.empty());

        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        for (String sourceId : List.of("musicbrainz", "discogs", "lastfm")) {
            verify(expandJobRepository).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq(sourceId), any());
        }
        verify(expandJobRepository, never()).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq("tribute-llm"), any());
    }

    @Test
    @DisplayName("deactivation deletes all expand jobs for the owner/artist")
    void deactivationDeletesJobs() {
        listener.onArtistDeactivated(new ArtistDeactivated(OWNER, ARTIST_ID));

        verify(expandJobRepository).deleteByOwnerAndArtistId(OWNER, ARTIST_ID);
    }
}
