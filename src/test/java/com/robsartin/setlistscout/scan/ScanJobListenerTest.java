package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import com.robsartin.setlistscout.shared.events.SettingsChanged;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanJobListenerTest {

    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 42L;
    private static final String FINGERPRINT = "abc123";

    private ScanJobRepository scanJobRepository;
    private SettingsService settingsService;
    private ShowSource ticketmaster;
    private ShowSource bandsintown;
    private ShowSource bandSite;
    private ScanJobListener listener;

    @BeforeEach
    void setUp() {
        scanJobRepository = mock(ScanJobRepository.class);
        settingsService = mock(SettingsService.class);
        ticketmaster = mock(ShowSource.class);
        bandsintown = mock(ShowSource.class);
        bandSite = mock(ShowSource.class);
        when(ticketmaster.id()).thenReturn("ticketmaster");
        when(bandsintown.id()).thenReturn("bandsintown");
        when(bandSite.id()).thenReturn("band-site");
        when(settingsService.locationFingerprint(OWNER)).thenReturn(FINGERPRINT);

        listener = new ScanJobListener(scanJobRepository, List.of(ticketmaster, bandsintown, bandSite), settingsService);
    }

    @Test
    @DisplayName("activation enqueues one SCHEDULED, due-now, fingerprinted job per source")
    void activationEnqueuesOneJobPerSource() {
        when(scanJobRepository.existsByOwnerAndArtistIdAndSource(eq(OWNER), eq(ARTIST_ID), anyString())).thenReturn(false);

        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        ArgumentCaptor<ScanJob> captor = ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository, times(3)).save(captor.capture());
        List<ScanJob> saved = captor.getAllValues();
        assertThat(saved).extracting(ScanJob::getSource)
                .containsExactlyInAnyOrder("ticketmaster", "bandsintown", "band-site");
        for (ScanJob job : saved) {
            assertThat(job.getOwner()).isEqualTo(OWNER);
            assertThat(job.getArtistId()).isEqualTo(ARTIST_ID);
            assertThat(job.getStatus()).isEqualTo(JobStatus.SCHEDULED);
            assertThat(job.getAttempts()).isEqualTo(0);
            assertThat(job.getNextDueAt()).isCloseTo(Instant.now(), within(5, java.time.temporal.ChronoUnit.SECONDS));
            assertThat(job.getLocationFingerprint()).isEqualTo(FINGERPRINT);
        }
    }

    @Test
    @DisplayName("activation skips a source whose job already exists")
    void activationSkipsExistingSource() {
        when(scanJobRepository.existsByOwnerAndArtistIdAndSource(OWNER, ARTIST_ID, "ticketmaster")).thenReturn(true);
        when(scanJobRepository.existsByOwnerAndArtistIdAndSource(OWNER, ARTIST_ID, "bandsintown")).thenReturn(false);
        when(scanJobRepository.existsByOwnerAndArtistIdAndSource(OWNER, ARTIST_ID, "band-site")).thenReturn(false);

        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        ArgumentCaptor<ScanJob> captor = ArgumentCaptor.forClass(ScanJob.class);
        verify(scanJobRepository, times(2)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(ScanJob::getSource)
                .containsExactlyInAnyOrder("bandsintown", "band-site");
    }

    @Test
    @DisplayName("activation swallows a DataIntegrityViolationException from a racing redelivery")
    void activationSwallowsRaceOnSave() {
        when(scanJobRepository.existsByOwnerAndArtistIdAndSource(eq(OWNER), eq(ARTIST_ID), anyString())).thenReturn(false);
        when(scanJobRepository.save(any(ScanJob.class))).thenThrow(new DataIntegrityViolationException("dup"));

        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        verify(scanJobRepository, times(3)).save(any(ScanJob.class));
    }

    @Test
    @DisplayName("deactivation deletes all scan jobs for the owner/artist")
    void deactivationDeletesJobs() {
        listener.onArtistDeactivated(new ArtistDeactivated(OWNER, ARTIST_ID));

        verify(scanJobRepository).deleteByOwnerAndArtistId(OWNER, ARTIST_ID);
    }

    @Test
    @DisplayName("settings-changed re-dues each of the owner's jobs and refreshes fingerprint")
    void settingsChangedReDuesJobs() {
        ScanJob job1 = new ScanJob(ARTIST_ID, "ticketmaster", JobStatus.SCHEDULED, 0, Instant.now().minusSeconds(3600), "stale-fp");
        ScanJob job2 = new ScanJob(ARTIST_ID, "bandsintown", JobStatus.SCHEDULED, 0, Instant.now().minusSeconds(3600), "stale-fp");
        when(scanJobRepository.findByOwner(OWNER)).thenReturn(List.of(job1, job2));

        listener.onSettingsChanged(new SettingsChanged(OWNER));

        assertThat(job1.getNextDueAt()).isCloseTo(Instant.now(), within(5, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(job1.getLocationFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(job2.getNextDueAt()).isCloseTo(Instant.now(), within(5, java.time.temporal.ChronoUnit.SECONDS));
        assertThat(job2.getLocationFingerprint()).isEqualTo(FINGERPRINT);
        verify(scanJobRepository).save(job1);
        verify(scanJobRepository).save(job2);
        verify(scanJobRepository, never()).deleteByOwnerAndArtistId(anyString(), anyLong());
    }
}
