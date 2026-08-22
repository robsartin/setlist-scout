package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.service.TestAppProperties;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.AdminGuard;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ui.ExtendedModelMap;
import org.springframework.ui.Model;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShowControllerTest {

    private static final String OWNER = "rob@example.com";

    private ShowRepository showRepository;
    private ArtistRepository artistRepository;
    private ScanJobRepository scanJobRepository;
    private SettingsService settingsService;
    private ArtistActivationService activationService;
    private ShowController controller;

    @BeforeEach
    void setUp() {
        showRepository = mock(ShowRepository.class);
        artistRepository = mock(ArtistRepository.class);
        scanJobRepository = mock(ScanJobRepository.class);
        settingsService = mock(SettingsService.class);
        activationService = mock(ArtistActivationService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        // #206 fix round 1 (Important 2): populateShows issues two narrow queries -- this stub
        // covers tributeArtistNames; activeArtistNames' findByOwnerAndStatusIn is left unstubbed
        // and relies on Mockito's default empty-list answer, same as every other unstubbed
        // List-returning call in this test class.
        when(artistRepository.findByOwnerAndSource(OWNER, ArtistSource.TRIBUTE_EXPANSION)).thenReturn(List.of());
        AdminGuard adminGuard = new AdminGuard(currentUser, TestAppProperties.withKeys());
        controller = new ShowController(showRepository, artistRepository, scanJobRepository,
                settingsService, currentUser, adminGuard, activationService);
    }

    // No id is set here (Show's id is JPA-generated, no setter) -- these controller-unit tests
    // never need it: ShowActionOutcome is built from the path-variable id the controller already
    // has, not from acted.getId(), and id-based repository lookups are stubbed by argument matcher
    // directly. Real generated-id behavior is covered by the Testcontainers-backed
    // web.ShowHideActionsTest and scan.HiddenShowSurvivesRescanTest.
    private static Show show(String artistName) {
        return show(artistName, "ticketmaster");
    }

    /** Same as {@link #show(String)}, but with an explicit source -- for #220's venue-filter fixtures. */
    private static Show show(String artistName, String source) {
        Show show = new Show(artistName, LocalDateTime.now().plusDays(5), "Moody Center", "Austin",
                BigDecimal.TEN, source, null, Show.Kind.MUSIC);
        show.setOwner(OWNER);
        return show;
    }

    @Test
    @DisplayName("scanNow (htmx) re-dues the owner's scan jobs and returns the queued confirmation fragment")
    void scanNowHtmxReturnsQueuedFragment() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Model model = new ExtendedModelMap();

        String view = controller.scanNow("hx", "eventDate", false, model);

        verify(scanJobRepository).redueAll(eq(OWNER), any(Instant.class));
        assertThat(view).isEqualTo("shows :: showsRegion");
        assertThat(model.getAttribute("scanQueued")).isEqualTo(true);
    }

    @Test
    @DisplayName("scanNow without htmx still re-dues the jobs and redirects (no-JS fallback)")
    void scanNowNonHtmxRedirects() {
        Model model = new ExtendedModelMap();

        String view = controller.scanNow(null, "eventDate", false, model);

        verify(scanJobRepository).redueAll(eq(OWNER), any(Instant.class));
        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("shows puts the owner's lowercased tribute-artist names in the model")
    void showsIncludesTributeArtistNames() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Artist tribute = new Artist("Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION, ArtistStatus.APPROVED,
                "expansion", "tribute act");
        when(artistRepository.findByOwnerAndSource(OWNER, ArtistSource.TRIBUTE_EXPANSION)).thenReturn(List.of(tribute));
        Model model = new ExtendedModelMap();

        controller.shows("eventDate", false, model);

        @SuppressWarnings("unchecked")
        Set<String> tributeNames = (Set<String>) model.getAttribute("tributeArtistNames");
        assertThat(tributeNames).containsExactly("damn the torpedoes");
    }

    @Test
    @DisplayName("#206 fix round 1 (Important 2): populateShows issues two narrow owner-scoped "
            + "queries and never loads the owner's whole catalog via findByOwner")
    void populateShowsUsesNarrowQueriesNotFullCatalogLoad() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Model model = new ExtendedModelMap();

        controller.shows("eventDate", false, model);

        verify(artistRepository).findByOwnerAndSource(OWNER, ArtistSource.TRIBUTE_EXPANSION);
        verify(artistRepository).findByOwnerAndStatusIn(OWNER, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED));
        verify(artistRepository, never()).findByOwner(anyString());
    }

    @Test
    @DisplayName("issue #166: the default (toggle off) list uses the hidden-excluding query")
    void defaultShowsQueryExcludesHiddenShows() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show visible = show("Wilco");
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of(visible)));
        Model model = new ExtendedModelMap();

        controller.shows("eventDate", false, model);

        assertThat(model.getAttribute("shows")).isEqualTo(List.of(visible));
        assertThat(model.getAttribute("showHidden")).isEqualTo(false);
        verify(showRepository, never())
                .findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(anyString(), any(), any());
    }

    @Test
    @DisplayName("issue #166: the toggle-on list uses the query that includes hidden shows")
    void toggleOnQueryIncludesHiddenShows() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show hidden = show("Wilco");
        when(showRepository.findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of(hidden)));
        Model model = new ExtendedModelMap();

        controller.shows("eventDate", true, model);

        assertThat(model.getAttribute("shows")).isEqualTo(List.of(hidden));
        assertThat(model.getAttribute("showHidden")).isEqualTo(true);
        verify(showRepository, never())
                .findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(anyString(), any(), any());
    }

    // ---- issue #220: hiddenCount respects the venue-follow filter --------------------------
    //
    // Owner decision recorded in the brief: hiddenCount means "shows you hid that you could
    // still see" -- it applies the SAME venue-follow filter #206 gave the display list, so the
    // "show hidden" toggle always reveals exactly the number it promised.
    //
    // Each test below asserts a NON-ZERO expected count. That's deliberate, not incidental: an
    // unstubbed findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNotNull would make hiddenCount
    // silently read as 0 under Mockito's default List answer, which would make an "excludes ->
    // expect 0" test pass vacuously whether or not the fix landed -- exactly the false-green shape
    // CLAUDE.md warns about elsewhere in this suite. Pairing every "should be excluded" row with a
    // control row that must still be counted keeps every assertion here non-zero, so a controller
    // that hasn't been taught to call the new repository method at all fails loudly (0 vs. an
    // expected count > 0), not silently.

    @Test
    @DisplayName("issue #220: hiddenCount excludes a hidden venue show whose performer is not an active artist")
    void hiddenCountExcludesVenueShowForInactivePerformer() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Show hiddenInactiveVenueShow = show("Unfollowed Act", "venue:www.capcitycomedy.com");
        hiddenInactiveVenueShow.setHiddenAt(Instant.now());
        // Control row: unrelated to the venue filter (ticketmaster source), must always count --
        // its presence is what makes "hiddenCount == 1" (not 0) prove exclusion actually happened.
        Show hiddenControl = show("Wilco");
        hiddenControl.setHiddenAt(Instant.now());
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNotNull(
                anyString(), any(), any())).thenReturn(
                        new java.util.ArrayList<>(List.of(hiddenInactiveVenueShow, hiddenControl)));
        // No active artists stubbed -- findByOwnerAndStatusIn defaults to empty (Mockito), so
        // "Unfollowed Act" has nothing to match against.
        Model model = new ExtendedModelMap();

        controller.shows("eventDate", false, model);

        assertThat(model.getAttribute("hiddenCount")).isEqualTo(1L);
    }

    @Test
    @DisplayName("issue #220: hiddenCount includes a hidden venue show whose performer is an active artist")
    void hiddenCountIncludesVenueShowForActivePerformer() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Show hiddenActiveVenueShow = show("Nick Mullen", "venue:www.capcitycomedy.com");
        hiddenActiveVenueShow.setHiddenAt(Instant.now());
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNotNull(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of(hiddenActiveVenueShow)));
        Artist active = artistWithId(1L, "Nick Mullen", ArtistSource.VENUE_EXPANSION, ArtistStatus.APPROVED);
        when(artistRepository.findByOwnerAndStatusIn(OWNER, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)))
                .thenReturn(List.of(active));
        Model model = new ExtendedModelMap();

        controller.shows("eventDate", false, model);

        assertThat(model.getAttribute("hiddenCount")).isEqualTo(1L);
    }

    /**
     * The regression guard named in #220's brief: {@code TicketmasterService} stores the EVENT
     * TITLE, not a catalog artist name, in {@code artistName} (see {@code
     * ShowController#populateShows}'s own comment) -- "A Very Merry Symphony ft. Austin Symphony
     * Orchestra" is a real hidden-able show in production, and that string is not, and never will
     * be, a catalog artist name. If the venue-follow filter were ever applied broadly instead of
     * gated on the {@code venue:} source prefix, this row -- and all 47 of the owner's other
     * Ticketmaster shows -- would silently stop being counted as hidden.
     */
    @Test
    @DisplayName("issue #220 regression guard: hiddenCount includes a hidden Ticketmaster show whose artist_name is an event title")
    void hiddenCountIncludesTicketmasterEventTitleShow() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Show hiddenTicketmasterShow = show("A Very Merry Symphony ft. Austin Symphony Orchestra", "ticketmaster");
        hiddenTicketmasterShow.setHiddenAt(Instant.now());
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNotNull(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of(hiddenTicketmasterShow)));
        // No active artists stubbed -- this event-title string would never match a catalog name
        // anyway, which is exactly the point: it must count regardless of activeArtistNames.
        Model model = new ExtendedModelMap();

        controller.shows("eventDate", false, model);

        assertThat(model.getAttribute("hiddenCount")).isEqualTo(1L);
    }

    @Test
    @DisplayName("issue #220: with showHidden=true, the extra rows revealed equal hiddenCount from the toggle-off render")
    void hiddenCountMatchesExtraRowsRevealedByToggle() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show visible = show("Wilco", "ticketmaster");
        Show hiddenTicketmaster = show("A Very Merry Symphony ft. Austin Symphony Orchestra", "ticketmaster");
        hiddenTicketmaster.setHiddenAt(Instant.now());
        Show hiddenActiveVenue = show("Nick Mullen", "venue:www.capcitycomedy.com");
        hiddenActiveVenue.setHiddenAt(Instant.now());
        Show hiddenInactiveVenue = show("Unfollowed Act", "venue:www.capcitycomedy.com");
        hiddenInactiveVenue.setHiddenAt(Instant.now());

        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of(visible)));
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNotNull(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(
                        List.of(hiddenTicketmaster, hiddenActiveVenue, hiddenInactiveVenue)));
        when(showRepository.findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(
                        List.of(visible, hiddenTicketmaster, hiddenActiveVenue, hiddenInactiveVenue)));
        Artist active = artistWithId(1L, "Nick Mullen", ArtistSource.VENUE_EXPANSION, ArtistStatus.APPROVED);
        when(artistRepository.findByOwnerAndStatusIn(OWNER, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)))
                .thenReturn(List.of(active));

        Model toggleOffModel = new ExtendedModelMap();
        controller.shows("eventDate", false, toggleOffModel);
        long hiddenCount = (long) toggleOffModel.getAttribute("hiddenCount");
        int toggleOffRowCount = ((List<?>) toggleOffModel.getAttribute("shows")).size();

        Model toggleOnModel = new ExtendedModelMap();
        controller.shows("eventDate", true, toggleOnModel);
        int toggleOnRowCount = ((List<?>) toggleOnModel.getAttribute("shows")).size();

        // hiddenInactiveVenue must count in neither number -- it's the same trap as the regression
        // guard above, just checked from the "toggle can't reveal what the count promised" angle.
        assertThat(hiddenCount).isEqualTo(2L);
        assertThat(toggleOnRowCount - toggleOffRowCount)
                .as("the extra rows the toggle reveals must equal hiddenCount from the toggle-off render")
                .isEqualTo((int) hiddenCount);
    }

    @Test
    @DisplayName("issue #166: hiding an owned show sets hiddenAt and saves it")
    void hideShowSetsHiddenAtAndSavesWhenOwned() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show target = show("Radiohead");
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(target));
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Model model = new ExtendedModelMap();

        controller.hideShow(9L, "eventDate", false, "hx", model);

        verify(showRepository).save(target);
        assertThat(target.getHiddenAt()).isNotNull();
    }

    @Test
    @DisplayName("issue #166: hiding another owner's show id is a no-op, not a leak")
    void hideShowIsNoOpWhenNotOwned() {
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.empty());
        Model model = new ExtendedModelMap();

        String view = controller.hideShow(9L, "eventDate", false, null, model);

        verify(showRepository, never()).save(any());
        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("issue #166: unhiding an owned show clears hiddenAt and saves it")
    void unhideShowClearsHiddenAtWhenOwned() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show target = show("Radiohead");
        target.setHiddenAt(Instant.now());
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(target));
        when(showRepository.findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of(target)));
        Model model = new ExtendedModelMap();

        controller.unhideShow(9L, "eventDate", true, "hx", model);

        verify(showRepository).save(target);
        assertThat(target.getHiddenAt()).isNull();
    }

    @Test
    @DisplayName("issue #166: unhiding another owner's show id is a no-op, not a leak")
    void unhideShowIsNoOpWhenNotOwned() {
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.empty());
        Model model = new ExtendedModelMap();

        String view = controller.unhideShow(9L, "eventDate", false, null, model);

        verify(showRepository, never()).save(any());
        assertThat(view).isEqualTo("redirect:/");
    }

    // ---- issue #223: hide-and-cancel -------------------------------------------------------

    private static Artist artistWithId(long id, String name, ArtistSource source, ArtistStatus status) {
        Artist artist = new Artist(name, source, status, null, null);
        org.springframework.test.util.ReflectionTestUtils.setField(artist, "id", id);
        return artist;
    }

    @Test
    @DisplayName("issue #223: hide-and-cancel hides the show and transitions a SEED artist to REMOVED")
    void hideAndCancelTransitionsSeedArtistToRemoved() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show target = show("Radiohead");
        target.setArtistId(42L);
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(target));
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Artist seedArtist = artistWithId(42L, "Radiohead", ArtistSource.SEED_LIST, ArtistStatus.SEED);
        when(artistRepository.findByIdAndOwner(42L, OWNER)).thenReturn(Optional.of(seedArtist));
        Model model = new ExtendedModelMap();

        controller.hideAndCancel(9L, "eventDate", false, "hx", model);

        verify(showRepository).save(target);
        assertThat(target.getHiddenAt()).isNotNull();
        verify(activationService).changeStatus(42L, OWNER, ArtistStatus.REMOVED);
    }

    @Test
    @DisplayName("issue #223: hide-and-cancel transitions a PENDING_REVIEW artist to REJECTED")
    void hideAndCancelTransitionsPendingReviewArtistToRejected() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show target = show("Nick Mullen");
        target.setArtistId(43L);
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(target));
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Artist pending = artistWithId(43L, "Nick Mullen", ArtistSource.VENUE_EXPANSION, ArtistStatus.PENDING_REVIEW);
        when(artistRepository.findByIdAndOwner(43L, OWNER)).thenReturn(Optional.of(pending));
        Model model = new ExtendedModelMap();

        controller.hideAndCancel(9L, "eventDate", false, "hx", model);

        verify(activationService).changeStatus(43L, OWNER, ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("issue #223: hide-and-cancel transitions an APPROVED (expansion-sourced) artist to REJECTED")
    void hideAndCancelTransitionsApprovedArtistToRejected() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show target = show("Damn the Torpedoes");
        target.setArtistId(44L);
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(target));
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Artist approved = artistWithId(44L, "Damn the Torpedoes", ArtistSource.TRIBUTE_EXPANSION, ArtistStatus.APPROVED);
        when(artistRepository.findByIdAndOwner(44L, OWNER)).thenReturn(Optional.of(approved));
        Model model = new ExtendedModelMap();

        controller.hideAndCancel(9L, "eventDate", false, "hx", model);

        verify(activationService).changeStatus(44L, OWNER, ArtistStatus.REJECTED);
    }

    @Test
    @DisplayName("issue #223: hide-and-cancel never bumps an already-REMOVED artist to REJECTED")
    void hideAndCancelLeavesAnAlreadyRemovedArtistAsRemoved() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show target = show("Old Seed");
        target.setArtistId(45L);
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(target));
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Artist removed = artistWithId(45L, "Old Seed", ArtistSource.SEED_LIST, ArtistStatus.REMOVED);
        when(artistRepository.findByIdAndOwner(45L, OWNER)).thenReturn(Optional.of(removed));
        Model model = new ExtendedModelMap();

        controller.hideAndCancel(9L, "eventDate", false, "hx", model);

        verify(activationService).changeStatus(45L, OWNER, ArtistStatus.REMOVED);
    }

    @Test
    @DisplayName("issue #223: a null artist_id hides the show and does nothing to any artist")
    void hideAndCancelWithNullArtistIdOnlyHidesTheShow() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show target = show("A Very Merry Symphony ft. Austin Symphony Orchestra");
        // artistId left null -- a Ticketmaster row from before #223, or otherwise unresolved.
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(target));
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        Model model = new ExtendedModelMap();

        controller.hideAndCancel(9L, "eventDate", false, "hx", model);

        verify(showRepository).save(target);
        assertThat(target.getHiddenAt()).isNotNull();
        verify(artistRepository, never()).findByIdAndOwner(any(), any());
        verify(activationService, never()).changeStatus(any(), any(), any());
    }

    @Test
    @DisplayName("issue #223: hide-and-cancel on another owner's show id is a no-op, not a leak")
    void hideAndCancelIsNoOpWhenShowNotOwned() {
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.empty());
        Model model = new ExtendedModelMap();

        String view = controller.hideAndCancel(9L, "eventDate", false, null, model);

        verify(showRepository, never()).save(any());
        verify(activationService, never()).changeStatus(any(), any(), any());
        assertThat(view).isEqualTo("redirect:/");
    }

    @Test
    @DisplayName("issue #223: an artist_id that doesn't resolve for this owner is treated like null -- "
            + "still hides the show, never touches another owner's artist")
    void hideAndCancelTreatsAForeignArtistIdAsAbsent() {
        SearchSettings settings = new SearchSettings(OWNER, "Austin", "TX", 50, 6);
        when(settingsService.getOrCreateSettings(OWNER)).thenReturn(settings);
        Show target = show("Somebody Else's Artist");
        target.setArtistId(999L);
        when(showRepository.findByIdAndOwner(9L, OWNER)).thenReturn(Optional.of(target));
        when(showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(
                anyString(), any(), any())).thenReturn(new java.util.ArrayList<>(List.of()));
        // Owner-scoped lookup finds nothing -- id 999 doesn't belong to this owner. Deliberately ALSO
        // stub the unscoped findById to return a real (foreign-owned) artist: a production regression
        // that swapped findByIdAndOwner for a plain findById would find and act on THIS row, which is
        // exactly what the verify(never()) below must catch -- an unstubbed findById (defaulting to
        // Optional.empty()) would let that regression sail through undetected.
        when(artistRepository.findByIdAndOwner(999L, OWNER)).thenReturn(Optional.empty());
        Artist foreignArtist = artistWithId(999L, "Somebody Else's Artist", ArtistSource.SEED_LIST, ArtistStatus.SEED);
        when(artistRepository.findById(999L)).thenReturn(Optional.of(foreignArtist));
        Model model = new ExtendedModelMap();

        controller.hideAndCancel(9L, "eventDate", false, "hx", model);

        verify(showRepository).save(target);
        assertThat(target.getHiddenAt()).isNotNull();
        verify(activationService, never()).changeStatus(any(), any(), any());
    }
}
