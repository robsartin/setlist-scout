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
    /** Fragment view name shared by every bare-fragment htmx response on this page. */
    private static final String CANDIDATES_APP_FRAGMENT = "candidates :: candidatesApp";

    /** DOM id of the "Run expansion now" button (candidates.html); rendered on every response. */
    private static final String EXPAND_NOW_TRIGGER = "expand-now";
    /**
     * DOM id of the admin-only cross-account expansion button. Unlike {@link #EXPAND_NOW_TRIGGER}
     * its form is conditional, which is why {@link #focusable} has to check before naming it.
     */
    private static final String ADMIN_EXPAND_NOW_TRIGGER = "admin-expand-now";

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
        boolean fragment = hxRequest != null && historyRestore == null;
        // A fragment swap (sidebar navigation) destroys the focused element like any other action,
        // so it gets the anchor. A full page render -- including a history restore -- must not carry
        // autofocus at all: the browser would honour it natively on load.
        populateCandidates(model, via, fragment ? ActionOutcome.anchor(null) : null);
        return fragment ? candidatesAppFragment(model) : "candidates";
    }

    /**
     * Resolves the current group and populates the model for either the full page or the
     * {@code candidatesApp} fragment. Returns the resolved current group's {@code via} (for
     * building a redirect URL after a non-htmx action), or {@code null} if nothing is pending.
     * <p>
     * {@code outcome} (issue #155) is the focus/announcement target the caller resolved before
     * this method re-queries current state; it's downgraded by {@link #focusable} when the element
     * it names won't be in the response, and published as the {@code outcome} model attribute --
     * absent entirely on a full page render, where {@code outcome} is {@code null} coming in.
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
    private String populateCandidates(Model model, String requestedVia, ActionOutcome outcome) {
        String owner = currentUser.email();
        var groups = CandidateGroups.from(
                artistRepository.countByStatusGroupedByViaAndSource(owner, ArtistStatus.PENDING_REVIEW));
        var resolved = CandidateGroups.resolve(groups, requestedVia);
        model.addAttribute("current", resolved.current());
        model.addAttribute("others", resolved.others());
        model.addAttribute("pendingCount", groups.stream().mapToLong(CandidateGroups.BaseArtistGroup::total).sum());
        Map<ArtistSource, List<Artist>> rowsByType = new LinkedHashMap<>();
        if (resolved.current() != null) {
            for (var rg : resolved.current().relationGroups()) {
                rowsByType.put(rg.source(), groupRows(owner, resolved.current().via(), rg.source()));
            }
            model.addAttribute("rowsByType", rowsByType);
        }
        ActionOutcome focusable = focusable(outcome, rowsByType, adminTriggerRenders(model));
        if (focusable != null) {
            model.addAttribute("outcome", focusable);
        }
        return resolved.current() != null ? resolved.current().via() : null;
    }

    /**
     * Fetches one group's pending rows for a base-artist {@code via} + relation {@code source} --
     * shared by the render path ({@link #populateCandidates}) and the bulk-action path ({@link
     * #reviewGroup}). {@code via} is either an actual {@code discoveredVia} value or {@link
     * CandidateGroups#UNGROUPED}, the sentinel {@link CandidateGroups#from} maps a null {@code
     * discoveredVia} to for display (issue #156): {@code discoveredVia = 'Ungrouped'} can never
     * match a NULL column in SQL, so that sentinel needs its own {@code IS NULL} query rather than
     * the exact-match one below.
     * <p>
     * BOTH branches are name-ordered (issue #155). "The next row" -- what {@link
     * ActionOutcome#afterRow} resolves and what {@link #focusable} then re-checks against these
     * very lists -- is only defined if the render and the successor lookup agree on an order the
     * database reproduces, so the Ungrouped bucket cannot be the one bucket left unordered.
     */
    private List<Artist> groupRows(String owner, String via, ArtistSource source) {
        return CandidateGroups.UNGROUPED.equals(via)
                ? artistRepository.findByOwnerAndStatusAndDiscoveredViaIsNullAndSourceOrderByNameAsc(
                        owner, ArtistStatus.PENDING_REVIEW, source)
                : artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                        owner, ArtistStatus.PENDING_REVIEW, via, source);
    }

    /**
     * Downgrades a focus target that won't be in the response to the group anchor. Two ways that
     * happens: the ROW successor picked before the mutation isn't among the rows about to render
     * (another tab decided it, or the group auto-advanced), or a TRIGGER names the admin button on
     * a page where its form doesn't render. Without this the response would carry an {@code
     * autofocus} for an element that isn't there, and focus would silently drop to {@code <body>}
     * again -- the exact failure issue #155 exists to remove.
     */
    private static ActionOutcome focusable(ActionOutcome outcome, Map<ArtistSource, List<Artist>> rowsByType,
                                           boolean adminTriggerRenders) {
        if (outcome == null) {
            return null;
        }
        return switch (outcome.focus()) {
            case ROW -> rowsByType.values().stream()
                    .flatMap(List::stream)
                    .anyMatch(a -> outcome.artistId().equals(a.getId()))
                    ? outcome : outcome.downgradedToAnchor();
            // The self-service expand-now button renders unconditionally, so it never needs the
            // check; the admin one is gated in the template, and an admin CAN reach that endpoint
            // with the form absent (a hand-rolled POST, or a page rendered before the allow-list
            // shrank), which would otherwise leave the response pointing at nothing.
            case TRIGGER -> adminTriggerRenders || !ADMIN_EXPAND_NOW_TRIGGER.equals(outcome.triggerId())
                    ? outcome : outcome.downgradedToAnchor();
            case ANCHOR -> outcome;
        };
    }

    /**
     * Whether candidates.html will actually render the admin's cross-account expansion form: the
     * same condition as its {@code th:if}, read from the model attributes {@link NavModelAdvice}
     * populated before this handler body ran. Read from the model rather than recomputed from
     * config so it can't drift from what the template branches on.
     */
    private static boolean adminTriggerRenders(Model model) {
        return Boolean.TRUE.equals(model.getAttribute("isAdmin"))
                && model.getAttribute("otherOwnerEmails") instanceof List<?> others && !others.isEmpty();
    }

    /**
     * Returns the bare {@code candidatesApp} fragment view, and flags the model so that fragment
     * also renders an out-of-band swap of the nav badge (issue #154): {@code fragments/layout.html}'s
     * badge sits OUTSIDE {@code #candidates-app}, so a normal htmx swap -- which only ever touches
     * its target's subtree -- can never reach it. {@code hx-swap-oob="true"} lets htmx patch it
     * anywhere in the response document regardless of the primary swap target (vendored htmx 2.0.3
     * finds it via a document-wide scan and matches it to the live DOM by id, independent of
     * nesting -- {@code allowNestedOobSwaps} defaults true and this app never overrides it).
     * <p>
     * Only set here, never for the full-page {@code "candidates"} view: that view already renders
     * {@code fragments/layout.html}'s own badge fresh (same shared {@code Model}, same overwritten
     * {@code pendingCount} -- see {@link #populateCandidates}), so a second copy of the same id
     * inside {@code <main>} would just be a duplicate-id no-op at best.
     */
    private String candidatesAppFragment(Model model) {
        model.addAttribute("oobNavBadge", true);
        return CANDIDATES_APP_FRAGMENT;
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
        return decide(id, ArtistStatus.APPROVED, "approve", "Approved", hxRequest, model);
    }

    /** Reject one candidate. Owner-scoped via changeStatus (no-op if this owner doesn't own {@code id}). */
    @PostMapping("/{id}/reject")
    public String reject(@PathVariable Long id, @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                         Model model) {
        return decide(id, ArtistStatus.REJECTED, "reject", "Rejected", hxRequest, model);
    }

    /**
     * The shared per-row decision path (issue #155). Resolves the focus successor BEFORE mutating,
     * while the acted-on row is still in the list: doing it afterwards would mean comparing names in
     * Java against an order Postgres produced, and the two disagree on case and punctuation.
     */
    private String decide(Long id, ArtistStatus status, String decision, String verb, String hxRequest, Model model) {
        String owner = currentUser.email();
        Artist acted = artistRepository.findByIdAndOwner(id, owner).orElse(null);
        String via = acted != null ? acted.getDiscoveredVia() : null;
        ActionOutcome outcome = acted == null
                ? ActionOutcome.anchor(null)
                : ActionOutcome.afterRow(
                        artistRepository.findByOwnerAndStatusAndDiscoveredViaAndSourceOrderByNameAsc(
                                owner, ArtistStatus.PENDING_REVIEW, via, acted.getSource()),
                        id, decision, verb + " " + acted.getName() + ".");
        activationService.changeStatus(id, owner, status);
        return actionResult(hxRequest, model, via, outcome);
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
            return actionResult(hxRequest, model, via, ActionOutcome.anchor(null));
        }
        List<Artist> rows = groupRows(currentUser.email(), via, type);
        for (Artist a : rows) {
            activationService.changeStatus(a.getId(), currentUser.email(), status);
        }
        String verb = status == ArtistStatus.APPROVED ? "Approved" : "Rejected";
        return actionResult(hxRequest, model, via, ActionOutcome.anchor(
                verb + " " + rows.size() + " " + CandidateGroups.label(type) + " from " + via + "."));
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
        List<Artist> pending = artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW);
        for (Artist a : pending) {
            activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.APPROVED);
        }
        return actionResult(hxRequest, model, null,
                ActionOutcome.anchor("Approved all " + pending.size() + " remaining candidates."));
    }

    /** Reject everything still pending in one action -- clears out a noisy batch after picking the keepers. */
    @PostMapping("/reject-all-pending")
    public String rejectAllPending(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                   Model model) {
        List<Artist> pending = artistRepository.findByOwnerAndStatus(currentUser.email(), ArtistStatus.PENDING_REVIEW);
        for (Artist a : pending) {
            activationService.changeStatus(a.getId(), currentUser.email(), ArtistStatus.REJECTED);
        }
        return actionResult(hxRequest, model, null,
                ActionOutcome.anchor("Rejected all " + pending.size() + " remaining candidates."));
    }

    /** Manually request expansion: mark all of this owner's expand jobs due-now (the poller drains them). */
    @PostMapping("/expand-now")
    public String expandNow(@RequestParam(required = false) String via,
                            @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                            Model model) {
        expandJobRepository.redueAll(currentUser.email(), java.time.Instant.now());
        return actionResult(hxRequest, model, via,
                ActionOutcome.trigger(EXPAND_NOW_TRIGGER, "Expansion requested."));
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
        return actionResult(hxRequest, model, null,
                ActionOutcome.trigger(ADMIN_EXPAND_NOW_TRIGGER, "Expansion requested for " + targetOwner + "."));
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
     * <p>
     * {@code outcome} (issue #155) carries the focus target through to {@link #populateCandidates}
     * unchanged -- it's meaningless on the non-htmx redirect branch below (a fresh GET recomputes
     * its own {@code null} outcome), but is harmless to have resolved either way.
     */
    private String actionResult(String hxRequest, Model model, String via, ActionOutcome outcome) {
        String resolvedVia = populateCandidates(model, via, outcome);
        if (hxRequest != null) {
            return candidatesAppFragment(model);
        }
        if (resolvedVia != null) {
            return "redirect:/artists/candidates?via="
                    + java.net.URLEncoder.encode(resolvedVia, java.nio.charset.StandardCharsets.UTF_8);
        }
        return "redirect:/artists/candidates";
    }
}
