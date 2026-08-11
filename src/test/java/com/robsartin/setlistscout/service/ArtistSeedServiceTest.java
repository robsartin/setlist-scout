package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ArtistSeedServiceTest {

    private static final String OWNER = "rob@example.com";

    private ArtistRepository artistRepository;
    private ArtistSeedService service;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        service = new ArtistSeedService(artistRepository);
    }

    @Test
    void addsATrimmedNewNameAsASeed() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Wilco")).thenReturn(false);

        boolean added = service.addSeedIfNew(OWNER, "  Wilco  ");

        assertThat(added).isTrue();
        ArgumentCaptor<Artist> saved = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Wilco");
        assertThat(saved.getValue().getOwner()).isEqualTo(OWNER);
        assertThat(saved.getValue().getSource()).isEqualTo(ArtistSource.SEED_LIST);
        assertThat(saved.getValue().getStatus()).isEqualTo(ArtistStatus.SEED);
    }

    @Test
    void skipsBlankAndCommentLines() {
        assertThat(service.addSeedIfNew(OWNER, "   ")).isFalse();
        assertThat(service.addSeedIfNew(OWNER, "")).isFalse();
        assertThat(service.addSeedIfNew(OWNER, null)).isFalse();
        assertThat(service.addSeedIfNew(OWNER, "# a comment")).isFalse();
        verify(artistRepository, never()).save(any(Artist.class));
    }

    @Test
    void skipsANameThatAlreadyExistsForTheOwner() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Dawes")).thenReturn(true);

        assertThat(service.addSeedIfNew(OWNER, "Dawes")).isFalse();
        verify(artistRepository, never()).save(any(Artist.class));
    }
}
