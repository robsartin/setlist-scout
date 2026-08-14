package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.scan.source.ShowSource;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import com.robsartin.setlistscout.shared.events.SettingsChanged;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScanJobListenerTest {

    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 42L;

    private ScanJobRepository scanJobRepository;
    private ShowSource ticketmaster;
    private ShowSource bandsintown;
    private ShowSource bandSite;
    private ScanJobListener listener;

    @BeforeEach
    void setUp() {
        scanJobRepository = mock(ScanJobRepository.class);
        ticketmaster = mock(ShowSource.class);
        bandsintown = mock(ShowSource.class);
        bandSite = mock(ShowSource.class);
        when(ticketmaster.id()).thenReturn("ticketmaster");
        when(bandsintown.id()).thenReturn("bandsintown");
        when(bandSite.id()).thenReturn("band-site");

        listener = new ScanJobListener(scanJobRepository, List.of(ticketmaster, bandsintown, bandSite));
    }

    @Test
    @DisplayName("activation issues one idempotent insertIfAbsent per source with SCHEDULED-shaped, due-now args")
    void activationEnqueuesOneJobPerSource() {
        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes"));

        for (String sourceId : List.of("ticketmaster", "bandsintown", "band-site")) {
            verify(scanJobRepository).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq(sourceId),
                    argThat(nextDueAt -> nextDueAt.isAfter(Instant.now().minusSeconds(5))
                            && nextDueAt.isBefore(Instant.now().plusSeconds(5))));
        }
    }

    @Test
    @DisplayName("deactivation deletes all scan jobs for the owner/artist")
    void deactivationDeletesJobs() {
        listener.onArtistDeactivated(new ArtistDeactivated(OWNER, ARTIST_ID));

        verify(scanJobRepository).deleteByOwnerAndArtistId(OWNER, ARTIST_ID);
    }

    @Test
    @DisplayName("settings-changed re-dues each of the owner's jobs via one bulk redueAll")
    void settingsChangedReDuesJobs() {
        listener.onSettingsChanged(new SettingsChanged(OWNER));

        verify(scanJobRepository).redueAll(eq(OWNER), any(Instant.class));
    }
}
