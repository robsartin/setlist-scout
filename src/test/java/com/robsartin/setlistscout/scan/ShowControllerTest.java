package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.service.TestAppProperties;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SettingsService;
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
    private ShowController controller;

    @BeforeEach
    void setUp() {
        showRepository = mock(ShowRepository.class);
        artistRepository = mock(ArtistRepository.class);
        scanJobRepository = mock(ScanJobRepository.class);
        settingsService = mock(SettingsService.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        when(artistRepository.findByOwnerAndSource(OWNER, ArtistSource.TRIBUTE_EXPANSION))
                .thenReturn(List.of());
        AppProperties appProperties = TestAppProperties.withKeys();
        controller = new ShowController(showRepository, artistRepository, scanJobRepository,
                settingsService, currentUser, appProperties);
    }

    // No id is set here (Show's id is JPA-generated, no setter) -- these controller-unit tests
    // never need it: ShowActionOutcome is built from the path-variable id the controller already
    // has, not from acted.getId(), and id-based repository lookups are stubbed by argument matcher
    // directly. Real generated-id behavior is covered by the Testcontainers-backed
    // web.ShowHideActionsTest and scan.HiddenShowSurvivesRescanTest.
    private static Show show(String artistName) {
        Show show = new Show(artistName, LocalDateTime.now().plusDays(5), "Moody Center", "Austin",
                BigDecimal.TEN, "ticketmaster", null);
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
        when(artistRepository.findByOwnerAndSource(OWNER, ArtistSource.TRIBUTE_EXPANSION))
                .thenReturn(List.of(tribute));
        Model model = new ExtendedModelMap();

        controller.shows("eventDate", false, model);

        @SuppressWarnings("unchecked")
        Set<String> tributeNames = (Set<String>) model.getAttribute("tributeArtistNames");
        assertThat(tributeNames).containsExactly("damn the torpedoes");
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
}
