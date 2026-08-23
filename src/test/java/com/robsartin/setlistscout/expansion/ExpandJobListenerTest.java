package com.robsartin.setlistscout.expansion;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.source.RelationSource;
import com.robsartin.setlistscout.shared.SharedScanOwner;
import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistShowsFound;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

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
    private RelationSource musicBrainz;
    private RelationSource discogs;
    private RelationSource lastFm;
    private RelationSource tributeLlm;
    private ExpandJobListener listener;

    @BeforeEach
    void setUp() {
        expandJobRepository = mock(ExpandJobRepository.class);
        musicBrainz = mock(RelationSource.class);
        discogs = mock(RelationSource.class);
        lastFm = mock(RelationSource.class);
        tributeLlm = mock(RelationSource.class);
        when(musicBrainz.id()).thenReturn("musicbrainz");
        when(musicBrainz.classification()).thenReturn(ArtistSource.MEMBER_EXPANSION);
        when(musicBrainz.appliesTo(any())).thenReturn(true);
        when(discogs.id()).thenReturn("discogs");
        when(discogs.classification()).thenReturn(ArtistSource.MEMBER_EXPANSION);
        when(discogs.appliesTo(any())).thenReturn(true);
        when(lastFm.id()).thenReturn("lastfm");
        when(lastFm.classification()).thenReturn(ArtistSource.SIMILAR_EXPANSION);
        when(lastFm.appliesTo(any())).thenReturn(true);
        when(tributeLlm.id()).thenReturn("tribute-llm");
        when(tributeLlm.classification()).thenReturn(ArtistSource.TRIBUTE_EXPANSION);
        // Mirrors TributeLlmSource#appliesTo: SEED-only, matching production behavior.
        when(tributeLlm.appliesTo(any())).thenAnswer(inv -> inv.getArgument(0) == ArtistStatus.SEED);

        listener = new ExpandJobListener(expandJobRepository, List.of(musicBrainz, discogs, lastFm, tributeLlm));
    }

    @Test
    @DisplayName("SEED artist activation enqueues every source, including tribute-llm")
    void seedArtistEnqueuesAllSources() {
        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes", ArtistStatus.SEED.name()));

        for (String sourceId : List.of("musicbrainz", "discogs", "lastfm", "tribute-llm")) {
            verify(expandJobRepository).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq(sourceId),
                    argThat(nextDueAt -> nextDueAt.isAfter(Instant.now().minusSeconds(5))
                            && nextDueAt.isBefore(Instant.now().plusSeconds(5))));
        }
    }

    @Test
    @DisplayName("APPROVED activation enqueues nothing on its own -- it enqueues once a show is "
            + "found, and tribute-llm stays SEED-only even then (#254)")
    void approvedArtistWaitsForShowsAndStillSkipsTribute() {
        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes", ArtistStatus.APPROVED.name()));

        verify(expandJobRepository, never()).insertIfAbsent(any(), any(), any(), any());

        listener.onArtistShowsFound(new ArtistShowsFound(OWNER, ARTIST_ID, "Dawes", ArtistStatus.APPROVED.name()));

        for (String sourceId : List.of("musicbrainz", "discogs", "lastfm")) {
            verify(expandJobRepository).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq(sourceId), any());
        }
        verify(expandJobRepository, never()).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq("tribute-llm"), any());
    }

    @Test
    @DisplayName("a null status (e.g. a legacy pre-#102 event replayed from the durable registry) "
            + "doesn't throw, and enqueues nothing -- an event we cannot classify must not be "
            + "read as a SEED (#254)")
    void nullStatusEnqueuesNothingWithoutThrowing() {
        listener.onArtistActivated(new ArtistActivated(OWNER, ARTIST_ID, "Dawes", null));

        verify(expandJobRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a null status on ArtistShowsFound still enqueues the non-SEED sources -- shows "
            + "are the evidence, the status only decides tribute eligibility (#254)")
    void nullStatusOnShowsFoundStillEnqueuesNonTributeSources() {
        listener.onArtistShowsFound(new ArtistShowsFound(OWNER, ARTIST_ID, "Dawes", null));

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

    // ---- #254: an artist earns expansion by having a show ---------------------------------

    @Test
    @DisplayName("APPROVED activation enqueues nothing -- it has not proven it plays anywhere (#254)")
    void approvedActivationEnqueuesNothing() {
        listener.onArtistActivated(
                new ArtistActivated(OWNER, ARTIST_ID, "Richard Dawkins", ArtistStatus.APPROVED.name()));

        verify(expandJobRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a show found for an APPROVED artist enqueues its expansion sources (#254)")
    void showsFoundEnqueuesForApprovedArtist() {
        listener.onArtistShowsFound(
                new ArtistShowsFound(OWNER, ARTIST_ID, "Dawes", ArtistStatus.APPROVED.name()));

        for (String sourceId : List.of("musicbrainz", "discogs", "lastfm")) {
            verify(expandJobRepository).insertIfAbsent(eq(OWNER), eq(ARTIST_ID), eq(sourceId), any());
        }
    }

    @Test
    @DisplayName("shows found still respect per-source eligibility -- tribute stays SEED-only (#254)")
    void showsFoundStillRespectsSourceEligibility() {
        listener.onArtistShowsFound(
                new ArtistShowsFound(OWNER, ARTIST_ID, "Dawes", ArtistStatus.APPROVED.name()));

        verify(expandJobRepository, never())
                .insertIfAbsent(any(), any(), eq("tribute-llm"), any());
    }

    @Test
    @DisplayName("a shared-scan owner never expands, even once shows are found (#163, #254)")
    void showsFoundStillGuardsSharedScanOwners() {
        listener.onArtistShowsFound(new ArtistShowsFound(SharedScanOwner.newKey(),
                ARTIST_ID, "Dawes", ArtistStatus.APPROVED.name()));

        verify(expandJobRepository, never()).insertIfAbsent(any(), any(), any(), any());
    }
}
