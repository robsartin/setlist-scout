package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.ArtistActivated;
import com.robsartin.setlistscout.shared.events.ArtistDeactivated;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ArtistActivationServiceTest {

    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 42L;

    private ArtistRepository artistRepository;
    private ApplicationEventPublisher publisher;
    private ArtistActivationService service;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new ArtistActivationService(artistRepository, publisher);
    }

    private Artist artistWithStatus(ArtistStatus status) {
        Artist artist = new Artist("Wilco", ArtistSource.SEED_LIST, status, null, null);
        artist.setOwner(OWNER);
        return artist;
    }

    @Test
    void pendingToApprovedPublishesArtistActivated() {
        Artist artist = artistWithStatus(ArtistStatus.PENDING_REVIEW);
        ReflectionTestUtils.setField(artist, "id", 5L);
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.changeStatus(ARTIST_ID, OWNER, ArtistStatus.APPROVED);

        assertThat(artist.getStatus()).isEqualTo(ArtistStatus.APPROVED);
        verify(artistRepository).save(artist);
        ArgumentCaptor<ArtistActivated> captor = ArgumentCaptor.forClass(ArtistActivated.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new ArtistActivated(OWNER, artist.getId(), artist.getName(), artist.getStatus().name()));
    }

    @Test
    void approvedToSeedPublishesNothing() {
        Artist artist = artistWithStatus(ArtistStatus.APPROVED);
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.changeStatus(ARTIST_ID, OWNER, ArtistStatus.SEED);

        assertThat(artist.getStatus()).isEqualTo(ArtistStatus.SEED);
        verify(artistRepository).save(artist);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void approvedToRejectedPublishesArtistDeactivated() {
        Artist artist = artistWithStatus(ArtistStatus.APPROVED);
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.changeStatus(ARTIST_ID, OWNER, ArtistStatus.REJECTED);

        assertThat(artist.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        verify(artistRepository).save(artist);
        ArgumentCaptor<ArtistDeactivated> captor = ArgumentCaptor.forClass(ArtistDeactivated.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ArtistDeactivated(OWNER, artist.getId()));
    }

    @Test
    void pendingToRejectedPublishesNothing() {
        Artist artist = artistWithStatus(ArtistStatus.PENDING_REVIEW);
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.changeStatus(ARTIST_ID, OWNER, ArtistStatus.REJECTED);

        assertThat(artist.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        verify(artistRepository).save(artist);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void rejectedToPendingPublishesNothing() {
        Artist artist = artistWithStatus(ArtistStatus.REJECTED);
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.changeStatus(ARTIST_ID, OWNER, ArtistStatus.PENDING_REVIEW);

        assertThat(artist.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        verify(artistRepository).save(artist);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void unknownIdIsANoOp() {
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.empty());

        service.changeStatus(ARTIST_ID, OWNER, ArtistStatus.APPROVED);

        verify(artistRepository, never()).save(any(Artist.class));
        verifyNoInteractions(publisher);
    }

    @Test
    void approvedToRemovedPublishesArtistDeactivated() {
        Artist artist = artistWithStatus(ArtistStatus.APPROVED);
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.changeStatus(ARTIST_ID, OWNER, ArtistStatus.REMOVED);

        assertThat(artist.getStatus()).isEqualTo(ArtistStatus.REMOVED);
        verify(artistRepository).save(artist);
        ArgumentCaptor<ArtistDeactivated> captor = ArgumentCaptor.forClass(ArtistDeactivated.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ArtistDeactivated(OWNER, artist.getId()));
    }

    // isActive is package-private specifically so this test (and ArtistSeedServiceTest) can pin
    // the one definition of "active" directly, rather than only asserting it indirectly through
    // changeStatus's event-publishing side effects above.
    @Test
    void isActiveClassifiesEachStatus() {
        assertThat(ArtistActivationService.isActive(ArtistStatus.SEED)).isTrue();
        assertThat(ArtistActivationService.isActive(ArtistStatus.APPROVED)).isTrue();
        assertThat(ArtistActivationService.isActive(ArtistStatus.PENDING_REVIEW)).isFalse();
        assertThat(ArtistActivationService.isActive(ArtistStatus.REJECTED)).isFalse();
        assertThat(ArtistActivationService.isActive(ArtistStatus.REMOVED))
                .as("REMOVED is a terminal, inactive status distinct from REJECTED -- a curated "
                        + "seed the owner no longer wants, not a reviewed-and-rejected candidate")
                .isFalse();
    }

    @Test
    void onSeedCreatedPublishesArtistActivated() {
        Artist saved = artistWithStatus(ArtistStatus.SEED);
        ReflectionTestUtils.setField(saved, "id", 5L);

        service.onSeedCreated(saved);

        ArgumentCaptor<ArtistActivated> captor = ArgumentCaptor.forClass(ArtistActivated.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new ArtistActivated(OWNER, saved.getId(), saved.getName(), saved.getStatus().name()));
    }
}
