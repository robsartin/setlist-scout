package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.service.ExpansionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ArtistControllerTest {

    private ArtistRepository artistRepository;
    private ExpansionService expansionService;
    private ArtistController controller;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        expansionService = mock(ExpansionService.class);
        controller = new ArtistController(artistRepository, expansionService);
    }

    private static Artist pending(String name, ArtistSource source) {
        return new Artist(name, source, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", "note");
    }

    @Test
    @DisplayName("list() splits pending into tribute acts and everyone else")
    void listGroupsPendingBySource() {
        when(artistRepository.findByStatus(ArtistStatus.PENDING_REVIEW)).thenReturn(List.of(
                pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION),
                pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION),
                pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION)));

        Model model = new ConcurrentModel();
        controller.list(model);

        assertThat((List<Artist>) model.getAttribute("pendingTributes"))
                .extracting(Artist::getName).containsExactly("Damn the Torpedoes");
        assertThat((List<Artist>) model.getAttribute("pendingOthers"))
                .extracting(Artist::getName).containsExactly("Mike Campbell", "Jackson Browne");
    }
}
