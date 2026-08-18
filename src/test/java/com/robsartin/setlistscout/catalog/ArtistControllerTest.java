package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private static final int PAGE_SIZE = 20;
    private static final int PAGE_SIZE_PLUS_ONE = PAGE_SIZE + 1;
    private static final List<ArtistStatus> ACTIVE_STATUSES = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    private ArtistRepository artistRepository;
    private CurrentUser currentUser;
    private ArtistActivationService activationService;
    private ArtistImportService importService;
    private ArtistController controller;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        activationService = mock(ArtistActivationService.class);
        // upload() (#177) delegates to ArtistImportService#queue, not seedService -- mocked here
        // (rather than a real instance over a mocked ArtistImportRepository) since its own
        // dedupe/skip/cap contract is exhaustively covered for real against Postgres by
        // ArtistImportUploadTest; this class only needs to prove the CONTROLLER dispatches to it
        // and flashes the count it returns.
        importService = mock(ArtistImportService.class);
        // Real seed service AND real name matcher over the mocked repo, so addSeed still asserts
        // on repository interactions and exercises the actual normalized-name duplicate check
        // (issue #124) rather than a stubbed existsBy call.
        ArtistSeedService seedService = new ArtistSeedService(artistRepository, mock(ArtistActivationService.class),
                new ArtistNameMatcher(artistRepository));
        // Real ArtistPager over the mocked repo (issue #174) -- same idiom as seedService above --
        // so pagination assembly (the n+1 fetch, hasNext/hasPrevious, cursor selection) is exercised
        // for real by stubbing ArtistRepository's keyset methods, without hitting Postgres. Page
        // size 20 matches application.yml's default, so PAGE_SIZE_PLUS_ONE below lines up with it.
        ArtistPager artistPager = new ArtistPager(artistRepository, PAGE_SIZE);
        controller = new ArtistController(artistRepository, mock(ArtistEdgeRepository.class), currentUser, seedService,
                activationService, mock(ArtistConnectionsService.class), importService, mock(ArtistImportRepository.class),
                artistPager);
    }

    private static Artist pending(String name, ArtistSource source) {
        return new Artist(name, source, ArtistStatus.PENDING_REVIEW, "Tom Petty and the Heartbreakers", "note");
    }

    // ArtistNameMatcher resolves a match via ArtistRepository#findByOwnerAndNormalizedName
    // (issue #124/#118/#176), so tests that need an existing name to match stub that instead of the
    // retired existsByOwnerAndNameIgnoreCase pre-check. See ArtistNameMatcherTest's identical
    // helper/ordering note.
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
        when(artistRepository.insertIfAbsent(eq(OWNER), eq(name), eq(ArtistNameNormalizer.normalize(name)),
                eq(ArtistSource.SEED_LIST.name()), eq(ArtistStatus.SEED.name()), isNull(), isNull(),
                any(Instant.class))).thenReturn(1);
        Artist resolved = new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        resolved.setOwner(OWNER);
        when(artistRepository.findByOwnerAndName(OWNER, name)).thenReturn(Optional.of(resolved));
        return resolved;
    }

    @Test
    @DisplayName("list() populates only the active (seed + approved) list -- pending review moved to Candidates")
    void listPopulatesActiveOnly() {
        Artist active = new Artist("Wilco", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        when(artistRepository.findActiveFirstPage(OWNER, ACTIVE_STATUSES, PAGE_SIZE_PLUS_ONE))
                .thenReturn(List.of(active));

        Model model = new ConcurrentModel();
        controller.list(null, null, null, null, model);

        assertThat((List<Artist>) model.getAttribute("active"))
                .extracting(Artist::getName).containsExactly("Wilco");
        assertThat(model.getAttribute("pendingTributes")).isNull();
        assertThat(model.getAttribute("pendingOthers")).isNull();
    }

    @Test
    @DisplayName("issue #174: a plain page load (no HX-Request) renders the full \"artists\" view and "
            + "does not announce -- the layout already carries the one real #sr-status node")
    void listPlainLoadRendersFullPageAndDoesNotAnnounce() {
        when(artistRepository.findActiveFirstPage(OWNER, ACTIVE_STATUSES, PAGE_SIZE_PLUS_ONE)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        String view = controller.list(null, null, null, null, model);

        assertThat(view).isEqualTo("artists");
        assertThat(model.getAttribute("announceActivePage")).isEqualTo(false);
    }

    @Test
    @DisplayName("issue #174: an htmx GET (Next/Previous click) renders the bare activeSection "
            + "fragment and announces")
    void listHtmxGetRendersFragmentAndAnnounces() {
        when(artistRepository.findActiveFirstPage(OWNER, ACTIVE_STATUSES, PAGE_SIZE_PLUS_ONE)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        String view = controller.list(null, null, "true", null, model);

        assertThat(view).isEqualTo("artists :: activeSection");
        assertThat(model.getAttribute("announceActivePage")).isEqualTo(true);
    }

    @Test
    @DisplayName("issue #174: a history-restore re-fetch (HX-Request + HX-History-Restore-Request "
            + "both set -- a Back navigation missing htmx's local history cache) renders the FULL "
            + "page, not the fragment, and does not announce, matching ReviewController#candidates")
    void listHistoryRestoreRendersFullPageAndDoesNotAnnounce() {
        when(artistRepository.findActiveFirstPage(OWNER, ACTIVE_STATUSES, PAGE_SIZE_PLUS_ONE)).thenReturn(List.of());

        Model model = new ConcurrentModel();
        String view = controller.list(null, null, "true", "true", model);

        assertThat(view).isEqualTo("artists");
        assertThat(model.getAttribute("announceActivePage")).isEqualTo(false);
    }

    @Test
    @DisplayName("issue #174: list() forwards the after cursor to ArtistRepository#findActiveAfter, "
            + "and the resulting page reports hasPrevious=true -- reaching here at all means a page "
            + "before this one exists")
    void listForwardsAfterCursorToNextPageQuery() {
        Artist beta = new Artist("Beta", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        when(artistRepository.findActiveAfter(OWNER, ACTIVE_STATUSES, "alpha", PAGE_SIZE_PLUS_ONE))
                .thenReturn(List.of(beta));

        Model model = new ConcurrentModel();
        controller.list("alpha", null, "true", null, model);

        ActivePage page = (ActivePage) model.getAttribute("activePage");
        assertThat(page.artists()).extracting(Artist::getName).containsExactly("Beta");
        assertThat(page.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("issue #174: list() forwards the before cursor to ArtistRepository#findActiveBefore "
            + "and reverses the DESCENDING result back to ascending order for display")
    void listForwardsBeforeCursorToPreviousPageQueryAndReverses() {
        Artist bravo = new Artist("Bravo", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        Artist alpha = new Artist("Alpha", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
        when(artistRepository.findActiveBefore(OWNER, ACTIVE_STATUSES, "charlie", PAGE_SIZE_PLUS_ONE))
                .thenReturn(List.of(bravo, alpha)); // DESCENDING, as findActiveBefore returns it

        Model model = new ConcurrentModel();
        controller.list(null, "charlie", "true", null, model);

        ActivePage page = (ActivePage) model.getAttribute("activePage");
        assertThat(page.artists()).extracting(Artist::getName).containsExactly("Alpha", "Bravo");
    }

    @Test
    @DisplayName("issue #174: a page shorter than the page size has no next, and no cursor was "
            + "requested so it has no previous either -- first-and-last-page control rendering")
    void listSinglePageHasNeitherNextNorPrevious() {
        when(artistRepository.findActiveFirstPage(OWNER, ACTIVE_STATUSES, PAGE_SIZE_PLUS_ONE))
                .thenReturn(List.of(new Artist("Only One", ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null)));

        Model model = new ConcurrentModel();
        controller.list(null, null, null, null, model);

        ActivePage page = (ActivePage) model.getAttribute("activePage");
        assertThat(page.hasNext()).isFalse();
        assertThat(page.hasPrevious()).isFalse();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.previousCursor()).isNull();
    }

    @Test
    @DisplayName("upload (#177) delegates the file to ArtistImportService#queue and flashes the "
            + "QUEUED count, not an \"added\" count -- the endpoint itself never touches seedService")
    void uploadDelegatesToImportServiceAndFlashesQueuedCount() throws IOException {
        when(importService.queue(eq(OWNER), any(BufferedReader.class))).thenReturn(3);
        String contents = "Wilco\nDawes\nThe National\n";
        MockMultipartFile file = new MockMultipartFile(
                "file", "artists.txt", "text/plain", contents.getBytes(StandardCharsets.UTF_8));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.upload(file, redirect);

        assertThat(view).isEqualTo("redirect:/artists");
        verify(importService).queue(eq(OWNER), any(BufferedReader.class));
        verify(artistRepository, never()).save(any(Artist.class));
        verify(artistRepository, never()).insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any());
        assertThat(redirect.getFlashAttributes().get("uploadMessage")).asString()
                .contains("Queued").contains("3").doesNotContain("Added");
    }

    @Test
    @DisplayName("upload reports \"Could not read that file.\" on IOException, without calling queue")
    void uploadHandlesIOExceptionWithoutQueueing() throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getInputStream()).thenThrow(new IOException("boom"));
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.upload(file, redirect);

        assertThat(view).isEqualTo("redirect:/artists");
        assertThat(redirect.getFlashAttributes().get("uploadMessage")).isEqualTo("Could not read that file.");
        verify(importService, never()).queue(any(), any());
    }

    @Test
    @DisplayName("upload with an empty file queues nothing and flashes a zero count")
    void uploadWithEmptyFileQueuesNothing() throws IOException {
        MockMultipartFile file = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        RedirectAttributesModelMap redirect = new RedirectAttributesModelMap();

        String view = controller.upload(file, redirect);

        assertThat(view).isEqualTo("redirect:/artists");
        verify(importService, never()).queue(any(), any());
        assertThat(redirect.getFlashAttributes().get("uploadMessage")).asString().contains("Queued 0");
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
        when(artistRepository.findActiveFirstPage(OWNER, ACTIVE_STATUSES, PAGE_SIZE_PLUS_ONE)).thenReturn(List.of());
        Model model = new ConcurrentModel();
        String view = controller.addSeed("Wilco", "true", model);

        assertThat(view).isEqualTo("artists :: activeSection");
        verify(artistRepository, never()).save(any(Artist.class));
        verify(artistRepository).insertIfAbsent(eq(OWNER), eq("Wilco"),
                eq(ArtistNameNormalizer.normalize("Wilco")), eq(ArtistSource.SEED_LIST.name()),
                eq(ArtistStatus.SEED.name()), isNull(), isNull(), any(Instant.class));
        assertThat(model.getAttribute("active")).isNotNull();
    }

    @Test
    @DisplayName("issue #174: after adding, the response lands on the FIRST page -- proved at the "
            + "unit level by asserting findActiveFirstPage was called and findActiveAfter/"
            + "findActiveBefore never were, not just that SOME page rendered")
    void addSeedLandsOnFirstPageNotACursorQuery() {
        stubNewSeedInsert("Wilco");
        when(artistRepository.findActiveFirstPage(OWNER, ACTIVE_STATUSES, PAGE_SIZE_PLUS_ONE)).thenReturn(List.of());

        controller.addSeed("Wilco", "true", new ConcurrentModel());

        verify(artistRepository).findActiveFirstPage(OWNER, ACTIVE_STATUSES, PAGE_SIZE_PLUS_ONE);
        verify(artistRepository, never()).findActiveAfter(any(), any(), any(), anyInt());
        verify(artistRepository, never()).findActiveBefore(any(), any(), any(), anyInt());
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
        verify(artistRepository).insertIfAbsent(eq(OWNER), eq("Wilco"),
                eq(ArtistNameNormalizer.normalize("Wilco")), eq(ArtistSource.SEED_LIST.name()),
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
        when(artistRepository.findActiveFirstPage(OWNER, ACTIVE_STATUSES, PAGE_SIZE_PLUS_ONE)).thenReturn(List.of());

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
