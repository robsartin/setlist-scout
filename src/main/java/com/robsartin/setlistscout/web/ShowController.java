package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.config.AppProperties;
import com.robsartin.setlistscout.domain.SearchSettings;
import com.robsartin.setlistscout.domain.Show;
import com.robsartin.setlistscout.repository.SearchSettingsRepository;
import com.robsartin.setlistscout.repository.ShowRepository;
import com.robsartin.setlistscout.service.GeocodingService;
import com.robsartin.setlistscout.service.ShowAggregationService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

@Controller
public class ShowController {

    private final ShowRepository showRepository;
    private final SearchSettingsRepository settingsRepository;
    private final ShowAggregationService showAggregationService;
    private final GeocodingService geocodingService;
    private final AppProperties appProperties;
    private final CurrentUser currentUser;

    public ShowController(ShowRepository showRepository,
                           SearchSettingsRepository settingsRepository,
                           ShowAggregationService showAggregationService,
                           GeocodingService geocodingService,
                           AppProperties appProperties,
                           CurrentUser currentUser) {
        this.showRepository = showRepository;
        this.settingsRepository = settingsRepository;
        this.showAggregationService = showAggregationService;
        this.geocodingService = geocodingService;
        this.appProperties = appProperties;
        this.currentUser = currentUser;
    }

    @GetMapping("/")
    public String shows(@RequestParam(defaultValue = "eventDate") String sort, Model model) {
        String owner = currentUser.email();
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

        model.addAttribute("shows", shows);
        model.addAttribute("currentSort", sort);
        model.addAttribute("settings", settings);
        return "shows";
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

    /** Manually trigger a show scan instead of waiting for the scheduled interval. */
    @PostMapping("/scan-now")
    public String scanNow() {
        showAggregationService.scanForShows(currentUser.email());
        return "redirect:/";
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
