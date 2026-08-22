package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.CsvResponses;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

import java.time.Instant;
import java.util.List;

/**
 * The shared-shows page (#163): shows for the artists two users both follow, at a location neither
 * of them has saved.
 * <p>
 * Both viewing AND creating are participant/allow-list based, not admin-only (#229) -- these are
 * shows both people would want, and any signed-in, allow-listed user may propose a pairing with
 * another. Every handler resolves the scan through {@code SharedScanService#requireVisible}, so a
 * non-participant gets a 404 rather than another pairing's data. There is deliberately no
 * consent/invite step before a new pairing is visible to both sides -- see {@link #create}.
 * <p>
 * #187: a user can be in more than one pairing. {@code GET /shared} shows the default -- the
 * first (oldest) of {@code SharedScanService#visibleTo}'s now-explicitly-ordered list, stable
 * across repeated loads. {@code GET /shared/{id}} shows one specific pairing, resolved through
 * {@code requireVisible} so a non-participant 404s exactly as they already do for
 * {@code /shared/{id}/settings}, rather than a selection mechanism that trusts the id off the
 * request. Either way {@link #populatePage} also lists every OTHER pairing the caller is in, so
 * the page can offer a picker to the rest -- none of them are left unreachable the way a bare
 * {@code scans.get(0)} used to leave them.
 */
@Controller
public class SharedScanController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    private final SharedScanService sharedScanService;
    private final SharedScanReconciler reconciler;
    private final ScanJobRepository scanJobRepository;
    private final CurrentUser currentUser;

    public SharedScanController(SharedScanService sharedScanService,
                                 SharedScanReconciler reconciler,
                                 ScanJobRepository scanJobRepository,
                                 CurrentUser currentUser) {
        this.sharedScanService = sharedScanService;
        this.reconciler = reconciler;
        this.scanJobRepository = scanJobRepository;
        this.currentUser = currentUser;
    }

    /** The caller's default pairing -- oldest, per {@code visibleTo}'s explicit order, stable across loads. */
    @GetMapping("/shared")
    public String page(Model model) {
        populatePage(model, currentUser.email(), null);
        return "shared";
    }

    /**
     * One specific pairing -- what the picker links to, and where {@link #create}/{@link
     * #updateSettings}/{@link #scanNow} send the caller back to, so acting on a non-default
     * pairing doesn't bounce the page to a different one.
     */
    @GetMapping("/shared/{id}")
    public String pageFor(@PathVariable Long id, Model model) {
        populatePage(model, currentUser.email(), id);
        return "shared";
    }

    /**
     * CSV download of one pairing's shows (issue #228), same nine columns as {@code
     * ShowController#showsCsv}. Resolved through {@link SharedScanService#requireVisible} exactly
     * like {@link #pageFor} -- a non-participant 404s here exactly as they do for the page itself,
     * never a quieter fallback (see {@code requireVisible}'s own javadoc for why). {@link
     * SharedScanService#showsFor} is the SAME method {@link #populatePage} calls for the page's
     * own {@code shows} model attribute, so this export is guaranteed to match the page with no
     * second query to drift out of step.
     * <p>
     * No venue-follow filter to reapply here, unlike {@code ShowController#showsCsv}: a shared
     * scan's synthetic owner key can never accumulate a venue-sourced show in the first place --
     * venue jobs are only ever enqueued against a real signed-in user's own email ({@code
     * VenueService#addVenue} via {@code CurrentUser}), and a generated {@code shared:<uuid>} key
     * can never sign in to reach that path. {@code showsFor} is already the single rule this page
     * runs on.
     */
    @GetMapping("/shared/{id}.csv")
    public ResponseEntity<byte[]> csv(@PathVariable Long id) {
        SharedScan scan = sharedScanService.requireVisible(currentUser.email(), id);
        List<Show> shows = sharedScanService.showsFor(scan);
        List<List<String>> rows = shows.stream().map(ShowCsv::row).toList();
        return CsvResponses.download("shared-" + id + ".csv", ShowCsv.HEADER, rows);
    }

    /**
     * Shared by {@link #page}, {@link #pageFor}, and {@link #create}'s htmx branch.
     * <p>
     * {@code requestedId == null} means "the default": {@code scans.get(0)} of the now-ordered
     * {@code visibleTo} list, which is what keeps a plain {@code GET /shared} stable across
     * repeated loads. A non-null {@code requestedId} resolves through {@code requireVisible} --
     * a 404 for a non-participant or a nonexistent id, never a quiet fallback to some other
     * pairing the caller happens to be in, which would just be a different-shaped way of leaking
     * whether a foreign id exists.
     * <p>
     * The {@code requestedId != null} branch is checked BEFORE the caller's own {@code scans} can
     * short-circuit anything: a caller with zero pairings of their own who asks for someone else's
     * id must still 404, not fall through to the "no pairing" empty state with a quiet 200. Getting
     * this order backwards was exactly the bug a caller with no scans exposed here -- see
     * {@code SharedScanControllerTest#nonParticipantGets404ForASpecificPairing}.
     * <p>
     * {@code otherScans} is every other pairing the caller participates in, filtered out of the
     * already owner-scoped {@code scans} list -- no extra query, and no separate access check
     * needed, since membership in that list already proves visibility.
     */
    private void populatePage(Model model, String email, Long requestedId) {
        List<SharedScan> scans = sharedScanService.visibleTo(email);
        SharedScan scan;
        if (requestedId != null) {
            scan = sharedScanService.requireVisible(email, requestedId);
        } else if (!scans.isEmpty()) {
            scan = scans.get(0);
        } else {
            return;
        }
        List<SharedScan> otherScans = scans.stream()
                .filter(s -> !s.getId().equals(scan.getId()))
                .toList();
        model.addAttribute("scan", scan);
        model.addAttribute("otherScans", otherScans);
        model.addAttribute("settings", sharedScanService.settingsFor(scan));
        model.addAttribute("shows", sharedScanService.showsFor(scan));
        model.addAttribute("otherParticipant", scan.otherParticipant(email));
        // Lets the page distinguish "you have nothing in common" from "nothing playing there".
        model.addAttribute("sharedArtistCount", sharedScanService.sharedArtistCount(scan));
    }

    /**
     * Create the pairing (#229: open to any signed-in, allow-listed user, not admin-only). The
     * other participant comes from the allow-list dropdown; {@code SharedScanService#create}
     * rejects a non-allow-listed {@code ownerB}, a self-pairing, or a duplicate of an existing
     * pairing (either direction) before any row is written -- see its {@code validateNewPairing}.
     * There is deliberately no consent step: the other participant learns of the pairing by it
     * simply appearing on their own {@code /shared} page, per the decision recorded on #229.
     * htmx request -&gt; the {@code content} fragment, re-populated with the new pairing (also
     * what gives the create form's {@code hx-disabled-elt="find button"} something to swap into,
     * so a double-click's second submit lands on a form that's already gone). Non-JS fallback ->
     * a plain redirect, like {@code ShowController#scanNow}'s non-htmx branch.
     * <p>
     * Both branches focus the NEWLY created pairing (#187) rather than the default: after
     * intentionally creating "Rob &amp; Spencer" the page should show that pairing, not silently
     * keep showing "Rob &amp; David" with the new one demoted to a picker entry, which would look
     * like the submit did nothing.
     * <p>
     * {@code justCreated} drives the fragment's own {@code autofocus} (shared.html): an
     * {@code outerHTML} swap destroys whatever was focused (the submit button, which this
     * response no longer even renders) and focus would otherwise drop to {@code <body>} -- same
     * anchor-focus fix as {@code shows.html}'s {@code showsRegion}, just with no row-level target
     * to prefer over it. Left unset (falsy in the template) on the plain GET, so a normal page
     * load doesn't steal focus.
     */
    @PostMapping("/shared")
    public String create(@RequestParam String label, @RequestParam String ownerB,
                          @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          Model model) {
        SharedScan created = sharedScanService.create(label, currentUser.email(), ownerB);
        if (hxRequest != null) {
            populatePage(model, currentUser.email(), created.getId());
            model.addAttribute("justCreated", true);
            return "shared :: content";
        }
        return "redirect:/shared/" + created.getId();
    }

    /**
     * Either participant may set where the shared scan looks. Publishes SettingsChanged, re-duing
     * its jobs. Redirects back to THIS pairing (#187), not the bare default -- otherwise editing a
     * non-default pairing would bounce the page to a different one and look like the edit was lost.
     */
    @PostMapping("/shared/{id}/settings")
    public String updateSettings(@PathVariable Long id,
                                  @RequestParam String postalCode,
                                  @RequestParam(defaultValue = "50") int radiusMiles,
                                  @RequestParam(defaultValue = "6") int monthsAhead) {
        SharedScan scan = sharedScanService.requireVisible(currentUser.email(), id);
        sharedScanService.updateSettings(scan, postalCode, radiusMiles, monthsAhead);
        return "redirect:/shared/" + id;
    }

    /**
     * Either participant may rename the shared scan's display label (#238) -- the only thing
     * distinguishing multiple pairings (#229) in the picker. Its own endpoint/form, not folded
     * into {@link #updateSettings}'s: the two validate independently -- a bad ZIP must not block a
     * valid rename, and a blank/too-long label must not block a valid settings save -- and
     * renaming is rare (this page's own UI guidance), so it does not belong sharing a submit
     * button with the location fields. Mirrors {@link #updateSettings} exactly: resolves through
     * {@code requireVisible} (a non-participant 404s, never 403 -- see that method's own javadoc
     * for why), and redirects back to THIS pairing rather than the bare default, so renaming a
     * non-default pairing doesn't bounce the page to a different one.
     */
    @PostMapping("/shared/{id}/label")
    public String rename(@PathVariable Long id, @RequestParam String label) {
        SharedScan scan = sharedScanService.requireVisible(currentUser.email(), id);
        sharedScanService.rename(scan, label);
        return "redirect:/shared/" + id;
    }

    /**
     * Re-check the intersection and mark this shared scan's jobs due now. There is no synchronous
     * scan to wait on -- the paced poller drains them -- so this queues and returns, exactly like
     * the Shows page's own "Scan now". Redirects back to THIS pairing, same reasoning as {@link
     * #updateSettings}.
     */
    @PostMapping("/shared/{id}/scan-now")
    public String scanNow(@PathVariable Long id) {
        SharedScan scan = sharedScanService.requireVisible(currentUser.email(), id);
        reconciler.reconcile(scan);
        scanJobRepository.redueAll(scan.getOwnerKey(), Instant.now());
        return "redirect:/shared/" + id;
    }
}
