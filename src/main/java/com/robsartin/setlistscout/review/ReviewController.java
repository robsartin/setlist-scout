package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/artists")
public class ReviewController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    private final ArtistRepository artistRepository;
    private final ExpandJobRepository expandJobRepository;
    private final CurrentUser currentUser;
    private final ArtistActivationService activationService;

    public ReviewController(ArtistRepository artistRepository, ExpandJobRepository expandJobRepository,
                           CurrentUser currentUser, ArtistActivationService activationService) {
        this.artistRepository = artistRepository;
        this.expandJobRepository = expandJobRepository;
        this.currentUser = currentUser;
        this.activationService = activationService;
    }

    /**
     * Process the whole pending list from the review form's per-artist radios: Accept -&gt; APPROVED,
     * Reject -&gt; REJECTED, Later (the default) -&gt; left PENDING_REVIEW. Iterates the owner's own
     * pending artists and reads each one's decision, so it can only ever touch this user's rows.
     * Redirects (rather than a fragment swap) because a batch touches the active/pending/rejected
     * lists at once.
     */
    @PostMapping("/review")
    public String review(@RequestParam Map<String, String> decisions) {
        for (Artist a : artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW)) {
            String decision = decisions.get("decision-" + a.getId());
            if ("accept".equals(decision)) {
                activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.APPROVED);
            } else if ("reject".equals(decision)) {
                activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.REJECTED);
            }
            // "later" (or missing) -> leave it PENDING_REVIEW for a future pass
        }
        return "redirect:/artists";
    }

    /** The Rejected page: this owner's rejected artists, reversible via Unreject. */
    @GetMapping("/rejected")
    public String rejected(Model model) {
        model.addAttribute("rejected",
                artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.REJECTED));
        return "rejected";
    }

    /** Move a rejected artist back into the pending review queue. Owner-scoped via setStatus. */
    @PostMapping("/{id}/unreject")
    public String unreject(@PathVariable Long id) {
        setStatus(id, ArtistStatus.PENDING_REVIEW);
        return "redirect:/artists";
    }

    /**
     * Take an artist off the active list (seed/approved) by rejecting it -- it stops being scanned
     * and lands in the Rejected list, reversible via Unreject. Owner-scoped via setStatus.
     */
    @PostMapping("/{id}/remove")
    public String remove(@PathVariable Long id) {
        setStatus(id, ArtistStatus.REJECTED);
        return "redirect:/artists";
    }

    /** Approve everything still pending in one action -- the fast path for "approve most, reject a few". */
    @PostMapping("/approve-all-pending")
    public String approveAllPending(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                    Model model) {
        for (Artist a : artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW)) {
            activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.APPROVED);
        }
        return pendingResult(hxRequest, model);
    }

    /** Reject everything still pending in one action -- clears out a noisy batch after picking the keepers. */
    @PostMapping("/reject-all-pending")
    public String rejectAllPending(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                   Model model) {
        for (Artist a : artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW)) {
            activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.REJECTED);
        }
        return pendingResult(hxRequest, model);
    }

    /** Manually request expansion: mark all of this owner's expand jobs due-now (the poller drains them). */
    @PostMapping("/expand-now")
    public String expandNow(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                            Model model) {
        expandJobRepository.redueAll(currentUser.email(), java.time.Instant.now());
        return pendingResult(hxRequest, model);
    }

    /** Scoped by owner so a user can only change the status of their own artists. */
    private void setStatus(Long id, ArtistStatus status) {
        activationService.changeStatus(id, currentUser.email(), status);
    }

    /** htmx request -> swap just the pending section; otherwise a normal redirect (no-JS fallback). */
    private String pendingResult(String hxRequest, Model model) {
        if (hxRequest != null) {
            populatePending(model, currentUser.email());
            return "artists :: pendingSection";
        }
        return "redirect:/artists";
    }

    private void populatePending(Model model, String owner) {
        List<Artist> pending = artistRepository.findByOwnerAndStatus(owner, ArtistStatus.PENDING_REVIEW);
        model.addAttribute("pendingTributes", pending.stream()
                .filter(a -> a.getSource() == ArtistSource.TRIBUTE_EXPANSION).toList());
        model.addAttribute("pendingOthers", pending.stream()
                .filter(a -> a.getSource() != ArtistSource.TRIBUTE_EXPANSION).toList());
    }
}
