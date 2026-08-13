package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.CurrentUser;
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

    private final ShowRepository showRepository;
    private final ArtistRepository artistRepository;
    private final ScanJobRepository scanJobRepository;
    private final SettingsService settingsService;
    private final CurrentUser currentUser;

    public ShowController(ShowRepository showRepository,
                           ArtistRepository artistRepository,
                           ScanJobRepository scanJobRepository,
                           SettingsService settingsService,
                           CurrentUser currentUser) {
        this.showRepository = showRepository;
        this.artistRepository = artistRepository;
        this.scanJobRepository = scanJobRepository;
        this.settingsService = settingsService;
        this.currentUser = currentUser;
    }

    @GetMapping("/")
    public String shows(@RequestParam(defaultValue = "eventDate") String sort, Model model) {
        String owner = currentUser.email();
        populateShows(model, owner, sort);
        return "shows";
    }

    /** Loads the owner's shows (sorted) plus their settings into the model. */
    private void populateShows(Model model, String owner, String sort) {
        SearchSettings settings = settingsService.getOrCreateSettings(owner);
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

    /**
     * Manually request a scan: mark all of this owner's scan jobs due-now (the paced poller picks
     * them up within a tick) and confirm. There's no synchronous scan to wait on in the per-unit
     * model, so this just queues -- newly found shows appear on later page loads as the poller drains.
     */
    @PostMapping("/scan-now")
    public String scanNow(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          @RequestParam(defaultValue = "eventDate") String sort, Model model) {
        String owner = currentUser.email();
        scanJobRepository.redueAll(owner, java.time.Instant.now(), settingsService.locationFingerprint(owner));
        if (hxRequest != null) {
            populateShows(model, owner, sort);
            model.addAttribute("scanQueued", true);
            return "shows :: showsRegion";
        }
        return "redirect:/";
    }
}
