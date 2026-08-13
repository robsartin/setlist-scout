package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExpandJobListenerTest {

    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 42L;

    private ExpandJobRepository expandJobRepository;
    private RelationSource musicBrainz;
    private RelationSource discogs;
    private RelationSource lastFm;
    private ExpandJobListener listener;

    @BeforeEach
    void setUp() {
        expandJobRepository = mock(ExpandJobRepository.class);
        musicBrainz = mock(RelationSource.class);
        discogs = mock(RelationSource.class);
        lastFm = mock(RelationSource.class);
        when(musicBrainz.id()).thenReturn("musicbrainz");
        when(discogs.id()).thenReturn("discogs");
        when(lastFm.id()).thenReturn("lastfm");

        listener = new ExpandJobListener(expandJobRepository, List.of(musicBrainz, discogs, lastFm));
    }

    @Test
    @DisplayName("activation issues one idempotent insertIfAbsent per source with SCHEDULED-shaped, due-now args")
    void activationEnqueuesOneJobPerSource() {
        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        for (String sourceId : List.of("musicbrainz", "discogs", "lastfm")) {
            verify(expandJobRepository).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq(sourceId),
                    argThat(nextDueAt -> nextDueAt.isAfter(Instant.now().minusSeconds(5))
                            && nextDueAt.isBefore(Instant.now().plusSeconds(5))));
        }
    }

    @Test
    @DisplayName("deactivation deletes all expand jobs for the owner/artist")
    void deactivationDeletesJobs() {
        listener.onArtistDeactivated(new ArtistDeactivated(OWNER, ARTIST_ID));

        verify(expandJobRepository).deleteByOwnerAndArtistId(OWNER, ARTIST_ID);
    }
}
