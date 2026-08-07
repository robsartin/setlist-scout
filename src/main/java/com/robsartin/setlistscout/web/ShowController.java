package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.domain.SearchSettings;
import com.robsartin.setlistscout.domain.Show;
import com.robsartin.setlistscout.repository.SearchSettingsRepository;
import com.robsartin.setlistscout.repository.ShowRepository;
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

    public ShowController(ShowRepository showRepository,
                           SearchSettingsRepository settingsRepository,
                           ShowAggregationService showAggregationService) {
        this.showRepository = showRepository;
        this.settingsRepository = settingsRepository;
        this.showAggregationService = showAggregationService;
    }

    @GetMapping("/")
    public String shows(@RequestParam(defaultValue = "eventDate") String sort, Model model) {
        SearchSettings settings = settingsRepository.findById(1L).orElseThrow();
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());

        List<Show> shows = showRepository.findByEventDateTimeBetweenOrderByEventDateTimeAsc(start, end);

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
    public String updateSettings(@RequestParam String city,
                                  @RequestParam String state,
                                  @RequestParam int radiusMiles,
                                  @RequestParam int monthsAhead) {
        SearchSettings settings = settingsRepository.findById(1L).orElseThrow();
        settings.setCity(city);
        settings.setState(state);
        settings.setRadiusMiles(radiusMiles);
        settings.setMonthsAhead(monthsAhead);
        settingsRepository.save(settings);
        return "redirect:/";
    }

    /** Manually trigger a show scan instead of waiting for the scheduled interval. */
    @PostMapping("/scan-now")
    public String scanNow() {
        showAggregationService.scanForShows();
        return "redirect:/";
    }
}
