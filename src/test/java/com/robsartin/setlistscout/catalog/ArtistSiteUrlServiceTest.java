package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.events.ArtistSiteUrlChanged;
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

/**
 * Issue #248: {@code recordOfficialSiteUrl} is the one write site that changes an artist's cached
 * official-site URL (#102) -- so it is also the one place that can read the OLD value before it's
 * overwritten. These tests pin exactly when it publishes {@link ArtistSiteUrlChanged} (a real prior
 * value that actually changed) versus when it stays silent (no prior value, or the same value
 * again) -- {@code scan.ShowRetirementListener} relies on that event existing if and only if there
 * is a genuine previous source to retire.
 */
class ArtistSiteUrlServiceTest {

    private static final String OWNER = "rob@example.com";
    private static final Long ARTIST_ID = 42L;

    private ArtistRepository artistRepository;
    private ApplicationEventPublisher publisher;
    private ArtistSiteUrlService service;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        publisher = mock(ApplicationEventPublisher.class);
        service = new ArtistSiteUrlService(artistRepository, publisher);
    }

    private Artist artistWithUrl(String url) {
        Artist artist = new Artist("Austin Symphony Orchestra", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        artist.setOwner(OWNER);
        ReflectionTestUtils.setField(artist, "id", ARTIST_ID);
        artist.setOfficialSiteUrl(url);
        return artist;
    }

    @Test
    void changingAnExistingUrlPublishesArtistSiteUrlChangedWithTheOldValue() {
        Artist artist = artistWithUrl("https://www.austinsymphony.org");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.recordOfficialSiteUrl(ARTIST_ID, OWNER, "https://austinsymphony.org/season-announcement/");

        assertThat(artist.getOfficialSiteUrl()).isEqualTo("https://austinsymphony.org/season-announcement/");
        verify(artistRepository).save(artist);
        ArgumentCaptor<ArtistSiteUrlChanged> captor = ArgumentCaptor.forClass(ArtistSiteUrlChanged.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new ArtistSiteUrlChanged(OWNER, ARTIST_ID, "https://www.austinsymphony.org"));
    }

    @Test
    void firstTimeUrlWithNoPriorValuePublishesNothing() {
        Artist artist = artistWithUrl(null);
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.recordOfficialSiteUrl(ARTIST_ID, OWNER, "https://www.austinsymphony.org");

        assertThat(artist.getOfficialSiteUrl()).isEqualTo("https://www.austinsymphony.org");
        verify(artistRepository).save(artist);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void settingTheSameUrlAgainPublishesNothing() {
        Artist artist = artistWithUrl("https://www.austinsymphony.org");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.recordOfficialSiteUrl(ARTIST_ID, OWNER, "https://www.austinsymphony.org");

        verify(artistRepository).save(artist);
        verify(publisher, never()).publishEvent(any());
    }

    @Test
    void clearingAnExistingUrlPublishesArtistSiteUrlChanged() {
        // Blank/null is a legitimate "changed" transition too -- #248's stated no-op rule is
        // specifically "unchanged, or old value was null", not "new value is null".
        Artist artist = artistWithUrl("https://www.austinsymphony.org");
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.of(artist));

        service.recordOfficialSiteUrl(ARTIST_ID, OWNER, null);

        assertThat(artist.getOfficialSiteUrl()).isNull();
        ArgumentCaptor<ArtistSiteUrlChanged> captor = ArgumentCaptor.forClass(ArtistSiteUrlChanged.class);
        verify(publisher).publishEvent(captor.capture());
        assertThat(captor.getValue()).isEqualTo(
                new ArtistSiteUrlChanged(OWNER, ARTIST_ID, "https://www.austinsymphony.org"));
    }

    @Test
    void unknownArtistIsANoOp() {
        when(artistRepository.findByIdAndOwner(ARTIST_ID, OWNER)).thenReturn(Optional.empty());

        service.recordOfficialSiteUrl(ARTIST_ID, OWNER, "https://example.com");

        verify(artistRepository, never()).save(any());
        verifyNoInteractions(publisher);
    }
}
