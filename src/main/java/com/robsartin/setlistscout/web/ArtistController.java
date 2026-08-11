package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.domain.Artist;
import com.robsartin.setlistscout.domain.ArtistSource;
import com.robsartin.setlistscout.domain.ArtistStatus;
import com.robsartin.setlistscout.repository.ArtistRepository;
import com.robsartin.setlistscout.service.ExpansionService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Controller
@RequestMapping("/artists")
public class ArtistController {

    private final ArtistRepository artistRepository;
    private final ExpansionService expansionService;

    public ArtistController(ArtistRepository artistRepository, ExpansionService expansionService) {
        this.artistRepository = artistRepository;
        this.expansionService = expansionService;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("active", artistRepository.findByStatusIn(
                List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)));
        List<Artist> pending = artistRepository.findByStatus(ArtistStatus.PENDING_REVIEW);
        model.addAttribute("pendingTributes", pending.stream()
                .filter(a -> a.getSource() == ArtistSource.TRIBUTE_EXPANSION).toList());
        model.addAttribute("pendingOthers", pending.stream()
                .filter(a -> a.getSource() != ArtistSource.TRIBUTE_EXPANSION).toList());
        model.addAttribute("rejected", artistRepository.findByStatus(ArtistStatus.REJECTED));
        return "artists";
    }

    @PostMapping("/seed")
    public String addSeed(@RequestParam String name) {
        if (!artistRepository.existsByNameIgnoreCase(name)) {
            artistRepository.save(new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null));
        }
        return "redirect:/artists";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id) {
        artistRepository.findById(id).ifPresent(a -> {
            a.setStatus(ArtistStatus.APPROVED);
            artistRepository.save(a);
        });
        return "redirect:/artists";
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id) {
        artistRepository.findById(id).ifPresent(a -> {
            a.setStatus(ArtistStatus.REJECTED);
            artistRepository.save(a);
        });
        return "redirect:/artists";
    }

    /** Manually trigger expansion instead of waiting for the scheduled scan. */
    @PostMapping("/expand-now")
    public String expandNow() {
        expansionService.expandAll();
        return "redirect:/artists";
    }
}
