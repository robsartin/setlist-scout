package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.charset.StandardCharsets;
import java.util.List;

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
    private CurrentUser currentUser;
    private ArtistController controller;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        // Real seed service over the mocked repo so add/upload still assert on repository interactions.
        ArtistSeedService seedService = new ArtistSeedService(artistRepository);
        controller = new ArtistController(artistRepository, currentUser, seedService);
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
