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

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    private final ArtistRepository artistRepository;
    private final ExpansionService expansionService;

    public ArtistController(ArtistRepository artistRepository, ExpansionService expansionService) {
        this.artistRepository = artistRepository;
        this.expansionService = expansionService;
    }

    @GetMapping
    public String list(Model model) {
        populateActive(model);
        populatePending(model);
        model.addAttribute("rejected", artistRepository.findByStatus(ArtistStatus.REJECTED));
        return "artists";
    }

    @PostMapping("/seed")
    public String addSeed(@RequestParam String name,
                          @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          Model model) {
        if (!artistRepository.existsByNameIgnoreCase(name)) {
            artistRepository.save(new Artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null));
        }
        if (hxRequest != null) {
            populateActive(model);
            return "artists :: activeSection";
        }
        return "redirect:/artists";
    }

    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id,
                          @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          Model model) {
        setStatus(id, ArtistStatus.APPROVED);
        return pendingResult(hxRequest, model);
    }

    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id,
                         @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                         Model model) {
        setStatus(id, ArtistStatus.REJECTED);
        return pendingResult(hxRequest, model);
    }

    /** Approve everything still pending in one action -- the fast path for "approve most, reject a few". */
    @PostMapping("/approve-all-pending")
    public String approveAllPending(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                    Model model) {
        for (Artist a : artistRepository.findByStatus(ArtistStatus.PENDING_REVIEW)) {
            a.setStatus(ArtistStatus.APPROVED);
            artistRepository.save(a);
        }
        return pendingResult(hxRequest, model);
    }

    /** Manually trigger expansion instead of waiting for the scheduled scan. */
    @PostMapping("/expand-now")
    public String expandNow(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                            Model model) {
        expansionService.expandAll();
        return pendingResult(hxRequest, model);
    }

    private void setStatus(Long id, ArtistStatus status) {
        artistRepository.findById(id).ifPresent(a -> {
            a.setStatus(status);
            artistRepository.save(a);
        });
    }

    /** htmx request -> swap just the pending section; otherwise a normal redirect (no-JS fallback). */
    private String pendingResult(String hxRequest, Model model) {
        if (hxRequest != null) {
            populatePending(model);
            return "artists :: pendingSection";
        }
        return "redirect:/artists";
    }

    private void populateActive(Model model) {
        model.addAttribute("active", artistRepository.findByStatusIn(
                List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)));
    }

    private void populatePending(Model model) {
        List<Artist> pending = artistRepository.findByStatus(ArtistStatus.PENDING_REVIEW);
        model.addAttribute("pendingTributes", pending.stream()
                .filter(a -> a.getSource() == ArtistSource.TRIBUTE_EXPANSION).toList());
        model.addAttribute("pendingOthers", pending.stream()
                .filter(a -> a.getSource() != ArtistSource.TRIBUTE_EXPANSION).toList());
    }
}
