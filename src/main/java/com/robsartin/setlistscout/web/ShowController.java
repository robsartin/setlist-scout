package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.config.AppProperties;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.SearchSettings;
import com.robsartin.setlistscout.domain.Show;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.repository.SearchSettingsRepository;
import com.robsartin.setlistscout.repository.ShowRepository;
import com.robsartin.setlistscout.service.AsyncScanRunner;
import com.robsartin.setlistscout.service.GeocodingService;
import com.robsartin.setlistscout.service.ScanStateService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class ShowController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    /** Shown while a scan is running; the poll swaps in the results once it finishes. */
    private static final String SCANNING_LABEL = "Scanning...";

    private final ShowRepository showRepository;
    private final SearchSettingsRepository settingsRepository;
    private final ArtistRepository artistRepository;
    private final AsyncScanRunner asyncScanRunner;
    private final ScanStateService scanState;
    private final GeocodingService geocodingService;
    private final AppProperties appProperties;
    private final CurrentUser currentUser;

    public ShowController(ShowRepository showRepository,
                           SearchSettingsRepository settingsRepository,
                           ArtistRepository artistRepository,
                           AsyncScanRunner asyncScanRunner,
                           ScanStateService scanState,
                           GeocodingService geocodingService,
                           AppProperties appProperties,
                           CurrentUser currentUser) {
        this.showRepository = showRepository;
        this.settingsRepository = settingsRepository;
        this.artistRepository = artistRepository;
        this.asyncScanRunner = asyncScanRunner;
        this.scanState = scanState;
        this.geocodingService = geocodingService;
        this.appProperties = appProperties;
        this.currentUser = currentUser;
    }

    @GetMapping("/")
    public String shows(@RequestParam(defaultValue = "eventDate") String sort, Model model) {
        String owner = currentUser.email();
        populateShows(model, owner, sort);
        model.addAttribute("scanning", scanState.isRunning(owner));
        model.addAttribute("scanLabel", SCANNING_LABEL);
        return "shows";
    }

    /** Loads the owner's shows (sorted) plus their settings into the model. */
    private void populateShows(Model model, String owner, String sort) {
        SearchSettings settings = getOrCreateSettings(owner);
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());

        List<Show> shows = showRepository.findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(owner, start, end);

        Comparator<Show> comparator = switch (sort) {
            case "discoveredAt" -> Comparator.comparing(Show::getDiscoveredAt);
            case "artist" -> Comparator.comparing(Show::getArtistName, String.CASE_INSENSITIVE_ORDER);
            case "venue" -> Comparator.comparing(Show::getVenueName, String.CASE_INSENSITIVE_ORDER);
            case "price" -> Comparator.comparing(Show::getPrice, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(Show::getEventDateTime);
        };
        shows.sort(comparator);

        Set<String> tributeArtistNames = artistRepository.findByOwnerAndSource(owner, ArtistSource.TRIBUTE_EXPANSION)
                .stream()
                .map(a -> a.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        model.addAttribute("shows", shows);
        model.addAttribute("currentSort", sort);
        model.addAttribute("settings", settings);
        model.addAttribute("tributeArtistNames", tributeArtistNames);
    }

    @PostMapping("/settings")
    public String updateSettings(@RequestParam String postalCode,
                                  @RequestParam int radiusMiles,
                                  @RequestParam int monthsAhead) {
        SearchSettings settings = getOrCreateSettings(currentUser.email());
        settings.setPostalCode(postalCode);
        settings.setRadiusMiles(radiusMiles);
        settings.setMonthsAhead(monthsAhead);
        // Geocode the ZIP to lat/long (+ display city/state). On failure, keep the last-known
        // coordinates so a bad/temporary lookup doesn't blank out the search location.
        geocodingService.geocode(postalCode).ifPresent(geo -> {
            settings.setLatitude(geo.latitude());
            settings.setLongitude(geo.longitude());
            settings.setCity(geo.city());
            settings.setState(geo.state());
        });
        settingsRepository.save(settings);
        return "redirect:/";
    }

    /**
     * Manually trigger a show scan. The scan runs async on a background executor (a full scan can
     * take a while), so this returns immediately: an htmx request gets the "Scanning" fragment that
     * polls {@code /scan-status}; a no-JS request just redirects while the scan runs in the
     * background. A second "Scan now" while one is already running is ignored (see AsyncScanRunner).
     */
    @PostMapping("/scan-now")
    public String scanNow(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          Model model) {
        String owner = currentUser.email();
        asyncScanRunner.startScan(owner);
        if (hxRequest != null) {
            model.addAttribute("scanning", true);
            model.addAttribute("scanLabel", SCANNING_LABEL);
            return "shows :: showsRegion";
        }
        return "redirect:/";
    }

    /**
     * Polled by the "Scanning" fragment (every 10s). While the scan runs it returns the scanning
     * fragment with one more dot; once it's done it returns the refreshed shows list, which htmx
     * swaps in -- replacing the polling element and so ending the poll.
     */
    @GetMapping("/scan-status")
    public String scanStatus(@RequestParam(defaultValue = "eventDate") String sort, Model model) {
        String owner = currentUser.email();
        if (scanState.isRunning(owner)) {
            model.addAttribute("scanning", true);
            model.addAttribute("scanLabel", SCANNING_LABEL);
            return "shows :: showsRegion";
        }
        populateShows(model, owner, sort);
        model.addAttribute("scanning", false);
        model.addAttribute("justScanned", true);
        return "shows :: showsRegion";
    }

    /** The user's settings, creating a default row (default ZIP, geocoded) on their first visit. */
    private SearchSettings getOrCreateSettings(String owner) {
        return settingsRepository.findByOwner(owner).orElseGet(() -> {
            var d = appProperties.defaults();
            SearchSettings settings = new SearchSettings(owner, d.city(), d.state(), d.radiusMiles(), d.monthsAhead());
            settings.setPostalCode(d.postalCode());
            geocodingService.geocode(d.postalCode()).ifPresent(geo -> {
                settings.setLatitude(geo.latitude());
                settings.setLongitude(geo.longitude());
                settings.setCity(geo.city());
                settings.setState(geo.state());
            });
            return settingsRepository.save(settings);
        });
    }
}
