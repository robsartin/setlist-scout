package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    @DisplayName("activation enqueues one SCHEDULED, due-now job per source")
    void activationEnqueuesOneJobPerSource() {
        when(expandJobRepository.existsByOwnerAndArtistIdAndSource(eq(OWNER), eq(ARTIST_ID), anyString())).thenReturn(false);

        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        ArgumentCaptor<ExpandJob> captor = ArgumentCaptor.forClass(ExpandJob.class);
        verify(expandJobRepository, times(3)).save(captor.capture());
        List<ExpandJob> saved = captor.getAllValues();
        assertThat(saved).extracting(ExpandJob::getSource)
                .containsExactlyInAnyOrder("musicbrainz", "discogs", "lastfm");
        for (ExpandJob job : saved) {
            assertThat(job.getOwner()).isEqualTo(OWNER);
            assertThat(job.getArtistId()).isEqualTo(ARTIST_ID);
            assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
            assertThat(job.getAttempts()).isEqualTo(0);
            assertThat(job.getNextDueAt()).isCloseTo(Instant.now(), within(5, java.time.temporal.ChronoUnit.SECONDS));
        }
    }

    @Test
    @DisplayName("activation skips a source whose job already exists")
    void activationSkipsExistingSource() {
        when(expandJobRepository.existsByOwnerAndArtistIdAndSource(OWNER, ARTIST_ID, "musicbrainz")).thenReturn(true);
        when(expandJobRepository.existsByOwnerAndArtistIdAndSource(OWNER, ARTIST_ID, "discogs")).thenReturn(false);
        when(expandJobRepository.existsByOwnerAndArtistIdAndSource(OWNER, ARTIST_ID, "lastfm")).thenReturn(false);

        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        ArgumentCaptor<ExpandJob> captor = ArgumentCaptor.forClass(ExpandJob.class);
        verify(expandJobRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ExpandJob::getSource)
                .containsExactlyInAnyOrder("discogs", "lastfm");
    }

    @Test
    @DisplayName("activation swallows a DataIntegrityViolationException from a racing redelivery")
    void activationSwallowsRaceOnSave() {
        when(expandJobRepository.existsByOwnerAndArtistIdAndSource(eq(OWNER), eq(ARTIST_ID), anyString())).thenReturn(false);
        when(expandJobRepository.save(any(ExpandJob.class))).thenThrow(new DataIntegrityViolationException("dup"));

        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        verify(expandJobRepository, times(3)).save(any(ExpandJob.class));
    }

    @Test
    @DisplayName("deactivation deletes all expand jobs for the owner/artist")
    void deactivationDeletesJobs() {
        listener.onArtistDeactivated(new ArtistDeactivated(OWNER, ARTIST_ID));

        verify(expandJobRepository).deleteByOwnerAndArtistId(OWNER, ARTIST_ID);
    }
}
