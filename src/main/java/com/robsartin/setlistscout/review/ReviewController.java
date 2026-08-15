package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequestMapping("/artists")
public class ReviewController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    /** Page size for a lazy-loaded group's rows on the Candidates page. */
    private static final int ROWS_PAGE = 25;

    private final ArtistRepository artistRepository;
    private final ExpandJobRepository expandJobRepository;
    private final CurrentUser currentUser;
    private final ArtistActivationService activationService;
    private final AppProperties appProperties;

    public ReviewController(ArtistRepository artistRepository, ExpandJobRepository expandJobRepository,
                           CurrentUser currentUser, ArtistActivationService activationService,
                           AppProperties appProperties) {
        this.artistRepository = artistRepository;
        this.expandJobRepository = expandJobRepository;
        this.currentUser = currentUser;
        this.activationService = activationService;
        this.appProperties = appProperties;
    }

    /**
     * The Candidates page: this owner's pending candidates grouped by base artist (discoveredVia)
     * and relation type (member/similar/tribute), with counts only -- rows lazy-load per group via
     * {@link #candidateRows}. {@code pendingCount} for the global bar comes from {@link NavModelAdvice}.
     */
    @GetMapping("/candidates")
    public String candidates(Model model) {
        var counts = artistRepository.countByStatusGroupedByViaAndSource(
                currentUser.email(), ArtistStatus.PENDING_REVIEW);
        model.addAttribute("groups", CandidateGroups.from(counts));
        return "candidates";
    }

    /**
     * One group's page of pending rows, loaded lazily when its {@code <details>} is first expanded
     * (or via "Show more" for a subsequent page). Owner-scoped like everything else here.
     */
    @GetMapping("/candidates/rows")
    public String candidateRows(@RequestParam String via, @RequestParam ArtistSource type,
                                @RequestParam(defaultValue = "0") int offset, Model model) {
        var rows = artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource(
                currentUser.email(), ArtistStatus.PENDING_REVIEW, via, type,
                PageRequest.of(offset / ROWS_PAGE, ROWS_PAGE));
        model.addAttribute("rows", rows);
        model.addAttribute("via", via);
        model.addAttribute("type", type);
        model.addAttribute("nextOffset", offset + ROWS_PAGE);
        // Simple heuristic: a full page might mean more remain. Good enough at ROWS_PAGE granularity.
        model.addAttribute("hasMore", rows.size() == ROWS_PAGE);
        return "candidates :: groupRows";
    }

    /** The Rejected page: this owner's rejected artists, reversible via Unreject. */
    @GetMapping("/rejected")
    public String rejected(Model model) {
        model.addAttribute("rejected",
                artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.REJECTED));
        return "rejected";
    }

    /** Approve one candidate. Owner-scoped via changeStatus (no-op if this owner doesn't own {@code id}). */
    @PostMapping("/{id}/approve")
    public String approve(@PathVariable Long id, @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          Model model) {
        activationService.changeStatus(id, currentUser.email(), ArtistStatus.APPROVED);
        return rowResult(hxRequest, model);
    }

    /** Reject one candidate. Owner-scoped via changeStatus (no-op if this owner doesn't own {@code id}). */
    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                         Model model) {
        activationService.changeStatus(id, currentUser.email(), ArtistStatus.REJECTED);
        return rowResult(hxRequest, model);
    }

    /**
     * Approve or reject an entire relation group (one base artist x relation type) in one action.
     * Iterates only this owner's still-pending rows for that group, so a concurrent per-item action
     * on the same group can't be clobbered and another owner's rows are never touched.
     */
    @PostMapping("/candidates/group")
    public String reviewGroup(@RequestParam String via, @RequestParam ArtistSource type,
                              @RequestParam String decision,
                              @RequestHeader(value = HX_REQUEST, required = false) String hxRequest, Model model) {
        ArtistStatus status;
        if ("approve".equals(decision)) {
            status = ArtistStatus.APPROVED;
        } else if ("reject".equals(decision)) {
            status = ArtistStatus.REJECTED;
        } else {
            // Malformed decision: do nothing rather than silently defaulting to reject.
            return actionResult(hxRequest, model);
        }
        for (Artist a : artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSource(
                currentUser.email(), ArtistStatus.PENDING_REVIEW, via, type)) {
            activationService.changeStatus(a.getId(), currentUser.email(), status);
        }
        return actionResult(hxRequest, model);
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
        return actionResult(hxRequest, model);
    }

    /** Reject everything still pending in one action -- clears out a noisy batch after picking the keepers. */
    @PostMapping("/reject-all-pending")
    public String rejectAllPending(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                   Model model) {
        for (Artist a : artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW)) {
            activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.REJECTED);
        }
        return actionResult(hxRequest, model);
    }

    /** Manually request expansion: mark all of this owner's expand jobs due-now (the poller drains them). */
    @PostMapping("/expand-now")
    public String expandNow(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                            Model model) {
        expandJobRepository.redueAll(currentUser.email(), java.time.Instant.now());
        return actionResult(hxRequest, model);
    }

    /**
     * Admin-only cross-account escape hatch (#136): re-due a DIFFERENT owner's expand jobs.
     * Reuses the exact same redueAll mechanism as the self-service {@link #expandNow} above, just
     * parameterized by {@code targetOwner} instead of {@code currentUser.email()}. See
     * {@link #requireAdmin()} and {@code ShowController#requireAdmin} for the shared rationale.
     */
    @PostMapping("/admin/expand-now")
    public String adminExpandNow(@RequestParam String targetOwner,
                                 @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                 Model model) {
        requireAdmin();
        expandJobRepository.redueAll(targetOwner, java.time.Instant.now());
        return actionResult(hxRequest, model);
    }

    /**
     * Placeholder admin gate for #136: reuses the same config-driven allow-list pattern as
     * {@code SecurityConfig}'s OIDC login check (see {@code AppProperties.Auth#adminEmail}), not a
     * real roles system -- that would be real infrastructure (migration, entity, an admin-toggle
     * UI) for an app with exactly two allowed users today. Revisit if the app ever grows past that.
     */
    private void requireAdmin() {
        String owner = currentUser.email();
        if (owner == null || !owner.equalsIgnoreCase(appProperties.auth().adminEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
    }

    /** Scoped by owner so a user can only change the status of their own artists. */
    private void setStatus(Long id, ArtistStatus status) {
        activationService.changeStatus(id, currentUser.email(), status);
    }

    /**
     * Result for a per-item approve/reject: htmx request -&gt; a bare empty fragment swapped in for
     * the row itself (its form targets {@code closest .cand} with {@code hx-swap="outerHTML"}, so
     * the row simply disappears); otherwise a normal redirect to the Candidates page (no-JS
     * fallback). The group's summary count and the nav badge re-sync on that next full page load --
     * acceptable for v1; an htmx out-of-band count update is a follow-up, not built now.
     */
    private String rowResult(String hxRequest, Model model) {
        if (hxRequest != null) {
            return "candidates :: rowDone";
        }
        return "redirect:/artists/candidates";
    }

    /**
     * Result for a per-group bulk or global approve/reject: htmx request -&gt; swap just the
     * Candidates page's global bar (its pendingCount attribute comes from {@link NavModelAdvice},
     * already applied to the model for every request); otherwise a normal redirect to the Candidates
     * page (no-JS fallback). The group's own summary count re-syncs on that next full page load --
     * acceptable for v1; an htmx out-of-band count update is a follow-up, not built now.
     */
    private String actionResult(String hxRequest, Model model) {
        if (hxRequest != null) {
            return "candidates :: globalBar";
        }
        return "redirect:/artists/candidates";
    }
}
