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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressWarnings("unchecked")
class ArtistControllerTest {

    private static final String OWNER = "rob@example.com";

    private ArtistRepository artistRepository;
    private ExpansionService expansionService;
    private CurrentUser currentUser;
    private ArtistController controller;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        expansionService = mock(ExpansionService.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        controller = new ArtistController(artistRepository, expansionService, currentUser);
    }

    private static Artist pending(String name, ArtistSource source) {
        return new Artist(name, source, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", "note");
    }

    @Test
    @DisplayName("list() splits pending into tribute acts and everyone else")
    void listGroupsPendingBySource() {
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).thenReturn(List.of(
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

    @Test
    @DisplayName("approveAllPending approves every pending artist")
    void approveAllPendingApprovesEveryone() {
        Artist tribute = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION);
        Artist similar = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).thenReturn(List.of(tribute, similar));

        controller.approveAllPending(null, new ConcurrentModel());

        assertThat(tribute.getStatus()).isEqualTo(ArtistStatus.APPROVED);
        assertThat(similar.getStatus()).isEqualTo(ArtistStatus.APPROVED);
        verify(artistRepository).save(tribute);
        verify(artistRepository).save(similar);
    }

    @Test
    @DisplayName("rejectAllPending rejects every pending artist")
    void rejectAllPendingRejectsEveryone() {
        Artist tribute = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION);
        Artist similar = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).thenReturn(List.of(tribute, similar));

        controller.rejectAllPending(null, new ConcurrentModel());

        assertThat(tribute.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(similar.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        verify(artistRepository).save(tribute);
        verify(artistRepository).save(similar);
    }

    @Test
    @DisplayName("reject returns the pending-section fragment for an htmx request")
    void rejectReturnsFragmentForHtmx() {
        Artist a = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        when(artistRepository.findByIdAndOwner(5L, OWNER)).thenReturn(Optional.of(a));
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        String view = controller.reject(5L, "true", model);

        assertThat(view).isEqualTo("artists :: pendingSection");
        assertThat(a.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(model.getAttribute("pendingTributes")).isNotNull();
        assertThat(model.getAttribute("pendingOthers")).isNotNull();
    }

    @Test
    @DisplayName("reject redirects for a normal (non-htmx) request")
    void rejectRedirectsWithoutHtmx() {
        Artist a = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        when(artistRepository.findByIdAndOwner(5L, OWNER)).thenReturn(Optional.of(a));

        String view = controller.reject(5L, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists");
        assertThat(a.getStatus()).isEqualTo(ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("setSiteUrl stores the official-site URL on the owner's artist")
    void setSiteUrlStoresUrl() {
        Artist a = pending("Dawes", ArtistSource.SIMILAR_EXPANSION);
        when(artistRepository.findByIdAndOwner(7L, OWNER)).thenReturn(java.util.Optional.of(a));

        controller.setSiteUrl(7L, "https://dawestheband.com", null, new ConcurrentModel());

        assertThat(a.getOfficialSiteUrl()).isEqualTo("https://dawestheband.com");
        verify(artistRepository).save(a);
    }

    @Test
    @DisplayName("addSeed returns the active-section fragment for an htmx request")
    void addSeedReturnsFragmentForHtmx() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Wilco")).thenReturn(false);

        Model model = new ConcurrentModel();
        String view = controller.addSeed("Wilco", "true", model);

        assertThat(view).isEqualTo("artists :: activeSection");
        verify(artistRepository).save(org.mockito.ArgumentMatchers.any(Artist.class));
        assertThat(model.getAttribute("active")).isNotNull();
    }
}
