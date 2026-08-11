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
    private final CurrentUser currentUser;

    public ArtistController(ArtistRepository artistRepository, ExpansionService expansionService,
                           CurrentUser currentUser) {
        this.artistRepository = artistRepository;
        this.expansionService = expansionService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public String list(Model model) {
        String owner = currentUser.email();
        populateActive(model, owner);
        populatePending(model, owner);
        model.addAttribute("rejected", artistRepository.findByOwnerAndStatus(owner, ArtistStatus.REJECTED));
        return "artists";
    }

    @PostMapping("/seed")
    public String addSeed(@RequestParam String name,
                          @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          Model model) {
        String owner = currentUser.email();
        // Reject a blank/whitespace name: a nameless seed would search Ticketmaster with
        // keyword="" and pull back every local event (issue #49). Trim so " Wilco " != "Wilco".
        String trimmed = name == null ? "" : name.trim();
        if (!trimmed.isEmpty() && !artistRepository.existsByOwnerAndNameIgnoreCase(owner, trimmed)) {
            Artist artist = new Artist(trimmed, ArtistSource.SEED_LIST, ArtistStatus.SEED, null, null);
            artist.setOwner(owner);
            artistRepository.save(artist);
        }
        if (hxRequest != null) {
            populateActive(model, owner);
            return "artists :: activeSection";
        }
        return "redirect:/artists";
    }

    /** Set or clear an artist's official-site URL (scraped for tour dates); owner-scoped. */
    @PostMapping("/{id}/site-url")
    public String setSiteUrl(@PathVariable Long id,
                             @RequestParam String url,
                             @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                             Model model) {
        String owner = currentUser.email();
        artistRepository.findByIdAndOwner(id, owner).ifPresent(a -> {
            a.setOfficialSiteUrl(url.isBlank() ? null : url.trim());
            artistRepository.save(a);
        });
        if (hxRequest != null) {
            populateActive(model, owner);
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
        for (Artist a : artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW)) {
            a.setStatus(ArtistStatus.APPROVED);
            artistRepository.save(a);
        }
        return pendingResult(hxRequest, model);
    }

    /** Reject everything still pending in one action -- clears out a noisy batch after picking the keepers. */
    @PostMapping("/reject-all-pending")
    public String rejectAllPending(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                   Model model) {
        for (Artist a : artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW)) {
            a.setStatus(ArtistStatus.REJECTED);
            artistRepository.save(a);
        }
        return pendingResult(hxRequest, model);
    }

    /** Manually trigger expansion instead of waiting for the scheduled scan. */
    @PostMapping("/expand-now")
    public String expandNow(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                            Model model) {
        expansionService.expandAll(currentUser.email());
        return pendingResult(hxRequest, model);
    }

    /** Scoped by owner so a user can only change the status of their own artists. */
    private void setStatus(Long id, ArtistStatus status) {
        artistRepository.findByIdAndOwner(id, currentUser.email()).ifPresent(a -> {
            a.setStatus(status);
            artistRepository.save(a);
        });
    }

    /** htmx request -> swap just the pending section; otherwise a normal redirect (no-JS fallback). */
    private String pendingResult(String hxRequest, Model model) {
        if (hxRequest != null) {
            populatePending(model, currentUser.email());
            return "artists :: pendingSection";
        }
        return "redirect:/artists";
    }

    private void populateActive(Model model, String owner) {
        model.addAttribute("active", artistRepository.findByOwnerAndStatusIn(
                owner, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)));
    }

    private void populatePending(Model model, String owner) {
        List<Artist> pending = artistRepository.findByOwnerAndStatus(owner, ArtistStatus.PENDING_REVIEW);
        model.addAttribute("pendingTributes", pending.stream()
                .filter(a -> a.getSource() == ArtistSource.TRIBUTE_EXPANSION).toList());
        model.addAttribute("pendingOthers", pending.stream()
                .filter(a -> a.getSource() != ArtistSource.TRIBUTE_EXPANSION).toList());
    }
}
