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

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    @DisplayName("activation issues one idempotent insertIfAbsent per source with SCHEDULED-shaped, "
            + "due-now, fingerprinted args")
    void activationEnqueuesOneJobPerSource() {
        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        for (String sourceId : List.of("ticketmaster", "bandsintown", "band-site")) {
            verify(scanJobRepository).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq(sourceId),
                    argThat(nextDueAt -> nextDueAt.isAfter(Instant.now().minusSeconds(5))
                            && nextDueAt.isBefore(Instant.now().plusSeconds(5))),
                    eq(FINGERPRINT));
        }
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
