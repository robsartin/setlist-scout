package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.service.ArtistSeedService;
import com.robsartin.setlistscout.service.ExpansionService;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
        // Real seed service over the mocked repo so add/upload still assert on repository interactions.
        ArtistSeedService seedService = new ArtistSeedService(artistRepository);
        controller = new ArtistController(artistRepository, expansionService, currentUser, seedService);
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
    @DisplayName("review applies accept/reject/later across the pending batch in one submit")
    void reviewAppliesEachDecision() {
        Artist accept = pending("Mike Campbell", ArtistSource.MEMBER_EXPANSION);
        Artist reject = pending("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION);
        Artist later = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        ReflectionTestUtils.setField(accept, "id", 1L);
        ReflectionTestUtils.setField(reject, "id", 2L);
        ReflectionTestUtils.setField(later, "id", 3L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(accept, reject, later));

        String view = controller.review(Map.of(
                "decision-1", "accept",
                "decision-2", "reject",
                "decision-3", "later"));

        assertThat(view).isEqualTo("redirect:/artists");
        assertThat(accept.getStatus()).isEqualTo(ArtistStatus.APPROVED);
        assertThat(reject.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        assertThat(later.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        verify(artistRepository).save(accept);
        verify(artistRepository).save(reject);
        verify(artistRepository, never()).save(later);
    }

    @Test
    @DisplayName("an artist with no submitted decision defaults to Later (left pending)")
    void reviewLeavesUndecidedPending() {
        Artist later = pending("Jackson Browne", ArtistSource.SIMILAR_EXPANSION);
        ReflectionTestUtils.setField(later, "id", 7L);
        when(artistRepository.findByOwnerAndStatus(OWNER, ArtistStatus.PENDING_REVIEW))
                .thenReturn(List.of(later));

        controller.review(Map.of()); // nothing submitted for this artist

        assertThat(later.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        verify(artistRepository, never()).save(later);
    }

    @Test
    @DisplayName("unreject moves a rejected artist back to pending review")
    void unrejectMovesBackToPending() {
        Artist a = new Artist("Jackson Browne", ArtistSource.SIMILAR_EXPANSION,
                ArtistStatus.REJECTED, "via", "note");
        when(artistRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(a));

        String view = controller.unreject(9L);

        assertThat(view).isEqualTo("redirect:/artists");
        assertThat(a.getStatus()).isEqualTo(ArtistStatus.PENDING_REVIEW);
        verify(artistRepository).save(a);
    }

    @Test
    @DisplayName("remove takes an active artist off the list by rejecting it")
    void removeRejectsActiveArtist() {
        Artist a = new Artist("Wilco", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        when(artistRepository.findByIdAndOwner(4L, OWNER)).thenReturn(Optional.of(a));

        String view = controller.remove(4L);

        assertThat(view).isEqualTo("redirect:/artists");
        assertThat(a.getStatus()).isEqualTo(ArtistStatus.REJECTED);
        verify(artistRepository).save(a);
    }

    @Test
    @DisplayName("upload adds new distinct names as seeds, skipping blanks, comments and duplicates")
    void uploadAddsNewSeeds() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Wilco")).thenReturn(false);
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Dawes")).thenReturn(true);
        String contents = "Wilco\n\n# a comment\nDawes\n"; // Wilco new; blank + comment skipped; Dawes exists
        MockMultipartFile file = new MockMultipartFile(
                "file", "artists.txt", "text/plain", contents.getBytes(StandardCharsets.UTF_8));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.upload(file, redirect);

        assertThat(view).isEqualTo("redirect:/artists");
        ArgumentCaptor<Artist> saved = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository, times(1)).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Wilco");
        assertThat(redirect.getFlashAttributes().get("uploadMessage")).asString().contains("1");
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

    @Test
    @DisplayName("addSeed ignores a blank/whitespace name (a blank seed would trigger a keyword-less search)")
    void addSeedIgnoresBlankName() {
        String view = controller.addSeed("   ", null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists");
        verify(artistRepository, never()).save(any(Artist.class));
    }

    @Test
    @DisplayName("addSeed trims surrounding whitespace before saving")
    void addSeedTrimsName() {
        when(artistRepository.existsByOwnerAndNameIgnoreCase(OWNER, "Wilco")).thenReturn(false);

        controller.addSeed("  Wilco  ", null, new ConcurrentModel());

        ArgumentCaptor<Artist> saved = ArgumentCaptor.forClass(Artist.class);
        verify(artistRepository).save(saved.capture());
        assertThat(saved.getValue().getName()).isEqualTo("Wilco");
    }
}
