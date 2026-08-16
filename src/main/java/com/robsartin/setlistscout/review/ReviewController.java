package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/artists")
public class ReviewController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";
    /**
     * htmx sets this ALONGSIDE {@code HX-Request} when a history navigation (e.g. Back) misses its
     * local history cache (vendored htmx's {@code historyCacheSize} is 10; production has ~294
     * sidebar groups, so a miss is routine) and has to re-fetch. That response gets {@code
     * swapInnerHTML(<body>, ...)} on the client, so it needs the FULL page -- answering it with the
     * bare fragment (as a plain {@code HX_REQUEST} request should get) strips the topbar/nav/h1/
     * page-sub until a manual reload.
     */
    private static final String HX_HISTORY_RESTORE_REQUEST = "HX-History-Restore-Request";

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
     * The Candidates page (issue #148): one group's full pending list at a time, biggest-first,
     * with a sidebar of the rest. {@code via} picks a specific group; omitted or stale (no pending
     * rows left) falls back to biggest-first via {@link CandidateGroups#resolve} -- the same
     * fallback rule every status-changing action below reuses as its auto-advance.
     */
    @GetMapping("/candidates")
    public String candidates(@RequestParam(required = false) String via,
                             @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                             @RequestHeader(value = HX_HISTORY_RESTORE_REQUEST, required = false) String historyRestore,
                             Model model) {
        populateCandidates(model, via);
        return (hxRequest != null && historyRestore == null) ? "candidates :: candidatesApp" : "candidates";
    }

    /**
     * Resolves the current group and populates the model for either the full page or the
     * {@code candidatesApp} fragment. Returns the resolved current group's {@code via} (for
     * building a redirect URL after a non-htmx action), or {@code null} if nothing is pending.
     * <p>
     * Also overwrites {@code pendingCount} (Minor 2, #148 fix round 3): {@link NavModelAdvice}'s
     * {@code @ModelAttribute} runs BEFORE the handler method body -- including before this action's
     * own {@code activationService.changeStatus} call -- so its {@code pendingCount} always reflects
     * PRE-action state. Recomputing it here, from the {@code groups} this method already fetched
     * fresh (no extra query: every pending row for the owner is in exactly one relation group, so
     * summing every group's total equals {@code countByOwnerAndStatus}), keeps the in-page counter
     * (unlike the nav badge, which stays stale -- see {@code .globalbar .count-label}) accurate
     * immediately after the mutation that just happened in this same request.
     */
    private String populateCandidates(Model model, String requestedVia) {
        String owner = currentUser.email();
        var groups = CandidateGroups.from(
                artistRepository.countByStatusGroupedByViaAndSource(owner, ArtistStatus.PENDING_REVIEW));
        var resolved = CandidateGroups.resolve(groups, requestedVia);
        model.addAttribute("current", resolved.current());
        model.addAttribute("others", resolved.others());
        model.addAttribute("pendingCount", groups.stream().mapToLong(CandidateGroups.BaseArtistGroup::total).sum());
        if (resolved.current() != null) {
            Map<ArtistSource, List<Artist>> rowsByType = new LinkedHashMap<>();
            for (var rg : resolved.current().relationGroups()) {
                rowsByType.put(rg.source(), artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                        owner, ArtistStatus.PENDING_REVIEW, resolved.current().via(), rg.source()));
            }
            model.addAttribute("rowsByType", rowsByType);
        }
        return resolved.current() != null ? resolved.current().via() : null;
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
        String owner = currentUser.email();
        String via = artistRepository.findByIdAndOwner(id, owner).map(Artist::getDiscoveredVia).orElse(null);
        activationService.changeStatus(id, owner, ArtistStatus.APPROVED);
        return actionResult(hxRequest, model, via);
    }

    /** Reject one candidate. Owner-scoped via changeStatus (no-op if this owner doesn't own {@code id}). */
    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                         Model model) {
        String owner = currentUser.email();
        String via = artistRepository.findByIdAndOwner(id, owner).map(Artist::getDiscoveredVia).orElse(null);
        activationService.changeStatus(id, owner, ArtistStatus.REJECTED);
        return actionResult(hxRequest, model, via);
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
            return actionResult(hxRequest, model, via);
        }
        for (Artist a : artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                currentUser.email(), ArtistStatus.PENDING_REVIEW, via, type)) {
            activationService.changeStatus(a.getId(), currentUser.email(), status);
        }
        return actionResult(hxRequest, model, via);
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
        return actionResult(hxRequest, model, null);
    }

    /** Reject everything still pending in one action -- clears out a noisy batch after picking the keepers. */
    @PostMapping("/reject-all-pending")
    public String rejectAllPending(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                   Model model) {
        for (Artist a : artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW)) {
            activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.REJECTED);
        }
        return actionResult(hxRequest, model, null);
    }

    /** Manually request expansion: mark all of this owner's expand jobs due-now (the poller drains them). */
    @PostMapping("/expand-now")
    public String expandNow(@RequestParam(required = false) String via,
                            @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                            Model model) {
        expandJobRepository.redueAll(currentUser.email(), java.time.Instant.now());
        return actionResult(hxRequest, model, via);
    }

    /**
     * Admin-only cross-account escape hatch (#136): re-due a DIFFERENT owner's expand jobs.
     * Reuses the exact same redueAll mechanism as the self-service {@link #expandNow} above, just
     * parameterized by {@code targetOwner} instead of {@code currentUser.email()}. See
     * {@link #requireAdmin()} and {@code ShowController#requireAdmin} for the shared rationale.
     * The response re-renders the ADMIN's own Candidates page (not the target owner's), so {@code
     * via} is {@code null} -- there's no "current group" carried across a cross-account action, it
     * always falls back to the admin's own biggest-first group via {@link #populateCandidates}.
     */
    @PostMapping("/admin/expand-now")
    public String adminExpandNow(@RequestParam String targetOwner,
                                 @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                 Model model) {
        requireAdmin();
        expandJobRepository.redueAll(targetOwner, java.time.Instant.now());
        return actionResult(hxRequest, model, null);
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
     * Shared response for every status-changing action on this page (issue #148): re-resolves
     * against {@code via} (the group the action just happened in, or {@code null} for a
     * whole-owner action) via {@link #populateCandidates} -- which either keeps that group current
     * (rows remain) or auto-advances to the next biggest (it's now empty), and populates the model
     * either way. htmx request -&gt; the {@code candidatesApp} fragment (both the group and sidebar
     * regions, always in sync, never stale). Non-JS fallback -&gt; redirect back to
     * {@code /artists/candidates}, carrying the resolved via as a query param so a full page load
     * lands in the same place htmx would have.
     */
    private String actionResult(String hxRequest, Model model, String via) {
        String resolvedVia = populateCandidates(model, via);
        if (hxRequest != null) {
            return "candidates :: candidatesApp";
        }
        if (resolvedVia != null) {
            return "redirect:/artists/candidates?via="
                    + java.net.URLEncoder.encode(resolvedVia, java.nio.charset.StandardCharsets.UTF_8);
        }
        return "redirect:/artists/candidates";
    }
}
