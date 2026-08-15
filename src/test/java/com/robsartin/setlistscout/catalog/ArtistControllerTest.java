package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    private ArtistActivationService activationService;
    private ArtistController controller;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        activationService = mock(ArtistActivationService.class);
        // Real seed service AND real name matcher over the mocked repo, so add/upload still assert
        // on repository interactions and exercise the actual normalized-name duplicate check
        // (issue #124) rather than a stubbed existsBy call.
        ArtistSeedService seedService = new ArtistSeedService(artistRepository, mock(ArtistActivationService.class),
                new ArtistNameMatcher(artistRepository));
        controller = new ArtistController(artistRepository, mock(ArtistEdgeRepository.class), currentUser, seedService,
                activationService, mock(ArtistConnectionsService.class));
    }

    private static Artist pending(String name, ArtistSource source) {
        return new Artist(name, source, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", "note");
    }

    // ArtistNameMatcher scans every one of the owner's existing artists via ArtistRepository#findByOwner
    // (issue #124/#118), so tests that need an existing name to match stub that instead of the retired
    // existsByOwnerAndNameIgnoreCase pre-check. See ArtistNameMatcherTest's identical helper/ordering note.
    private static ArtistNameStatusView existingArtist(Long id, String name, ArtistStatus status) {
        ArtistNameStatusView v = mock(ArtistNameStatusView.class);
        when(v.getId()).thenReturn(id);
        when(v.getName()).thenReturn(name);
        when(v.getStatus()).thenReturn(status);
        return v;
    }

    /**
     * Stubs a winning {@code ArtistRepository#insertIfAbsent} (returns 1) plus the {@code
     * findByOwnerAndName} resolve {@code ArtistSeedService#addSeedIfNew} performs right after
     * (issue #133) -- the mocked-repository equivalent of "this call's insert landed", for tests
     * that only care that a brand-new SEED artist gets created, not the race-loser path.
     */
    private Artist stubNewSeedInsert(String name) {
        when(artistRepository.insertIfAbsent(eq(OWNER), eq(name), eq(ArtistSource.SEED_LIST.name()),
                eq(ArtistStatus.SEED.name()), isNull(), isNull(), any(Instant.class))).thenReturn(1);
        Artist resolved = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        resolved.setOwner(OWNER);
        when(artistRepository.findByOwnerAndName(OWNER, name)).thenReturn(Optional.of(resolved));
        return resolved;
    }

    @Test
    @DisplayName("list() populates only the active (seed + approved) list -- pending review moved to Candidates")
    void listPopulatesActiveOnly() {
        Artist active = new Artist("Wilco", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        when(artistRepository.findByOwnerAndStatusIn(OWNER, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)))
                .thenReturn(List.of(active));

        Model model = new ConcurrentModel();
        controller.list(model);

        assertThat((List<Artist>) model.getAttribute("active"))
                .extracting(Artist::getName).containsExactly("Wilco");
        assertThat(model.getAttribute("pendingTributes")).isNull();
        assertThat(model.getAttribute("pendingOthers")).isNull();
    }

    @Test
    @DisplayName("upload adds new distinct names as seeds, skipping blanks, comments and duplicates")
    void uploadAddsNewSeeds() {
        ArtistNameStatusView dawes = existingArtist(1L, "Dawes", ArtistStatus.SEED);
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of(dawes));
        stubNewSeedInsert("Wilco");
        String contents = "Wilco\n\n# a comment\nDawes\n"; // Wilco new; blank + comment skipped; Dawes exists
        MockMultipartFile file = new MockMultipartFile(
                "file", "artists.txt", "text/plain", contents.getBytes(StandardCharsets.UTF_8));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.upload(file, redirect);

        assertThat(view).isEqualTo("redirect:/artists");
        verify(artistRepository, never()).save(any(Artist.class));
        verify(artistRepository, times(1)).insertIfAbsent(eq(OWNER), eq("Wilco"), eq(ArtistSource.SEED_LIST.name()),
                eq(ArtistStatus.SEED.name()), isNull(), isNull(), any(Instant.class));
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
        stubNewSeedInsert("Wilco");
        Model model = new ConcurrentModel();
        String view = controller.addSeed("Wilco", "true", model);

        assertThat(view).isEqualTo("artists :: activeSection");
        verify(artistRepository, never()).save(any(Artist.class));
        verify(artistRepository).insertIfAbsent(eq(OWNER), eq("Wilco"), eq(ArtistSource.SEED_LIST.name()),
                eq(ArtistStatus.SEED.name()), isNull(), isNull(), any(Instant.class));
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
        stubNewSeedInsert("Wilco");

        controller.addSeed("  Wilco  ", null, new ConcurrentModel());

        verify(artistRepository, never()).save(any(Artist.class));
        verify(artistRepository).insertIfAbsent(eq(OWNER), eq("Wilco"), eq(ArtistSource.SEED_LIST.name()),
                eq(ArtistStatus.SEED.name()), isNull(), isNull(), any(Instant.class));
    }

    @Test
    @DisplayName("removeFromSeed transitions the artist to REMOVED via ArtistActivationService, "
            + "not a direct repository save")
    void removeFromSeedInvokesActivationService() {
        controller.removeFromSeed(7L, null, new ConcurrentModel());

        verify(activationService).changeStatus(7L, OWNER, ArtistStatus.REMOVED);
        verify(artistRepository, never()).save(any(Artist.class));
    }

    @Test
    @DisplayName("removeFromSeed returns the active-section fragment for an htmx request")
    void removeFromSeedReturnsFragmentForHtmx() {
        Model model = new ConcurrentModel();
        when(artistRepository.findByOwnerAndStatusIn(OWNER, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)))
                .thenReturn(List.of());

        String view = controller.removeFromSeed(7L, "true", model);

        assertThat(view).isEqualTo("artists :: activeSection");
        assertThat(model.getAttribute("active")).isNotNull();
    }

    @Test
    @DisplayName("removeFromSeed redirects to /artists for a non-htmx request")
    void removeFromSeedRedirectsForNonHtmx() {
        String view = controller.removeFromSeed(7L, null, new ConcurrentModel());

        assertThat(view).isEqualTo("redirect:/artists");
    }
}
