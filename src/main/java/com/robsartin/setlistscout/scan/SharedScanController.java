package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.AdminGuard;
import com.robsartin.setlistscout.shared.CurrentUser;
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
 * Viewing is participant-based, not admin-only -- these are shows both people would want. Creating
 * a pairing is the admin action. Every handler resolves the scan through
 * {@code SharedScanService#requireVisible}, so a non-participant gets a 404 rather than another
 * pairing's data.
 */
@Controller
public class SharedScanController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    private final SharedScanService sharedScanService;
    private final SharedScanReconciler reconciler;
    private final ScanJobRepository scanJobRepository;
    private final CurrentUser currentUser;
    private final AdminGuard adminGuard;

    public SharedScanController(SharedScanService sharedScanService,
                                 SharedScanReconciler reconciler,
                                 ScanJobRepository scanJobRepository,
                                 CurrentUser currentUser,
                                 AdminGuard adminGuard) {
        this.sharedScanService = sharedScanService;
        this.reconciler = reconciler;
        this.scanJobRepository = scanJobRepository;
        this.currentUser = currentUser;
        this.adminGuard = adminGuard;
    }

    @GetMapping("/shared")
    public String page(Model model) {
        populatePage(model, currentUser.email());
        return "shared";
    }

    /** Shared by {@link #page} and {@link #create}'s htmx branch -- both render the same content fragment. */
    private void populatePage(Model model, String email) {
        List<SharedScan> scans = sharedScanService.visibleTo(email);
        if (!scans.isEmpty()) {
            SharedScan scan = scans.get(0);
            model.addAttribute("scan", scan);
            model.addAttribute("settings", sharedScanService.settingsFor(scan));
            model.addAttribute("shows", sharedScanService.showsFor(scan));
            model.addAttribute("otherParticipant", scan.otherParticipant(email));
            // Lets the page distinguish "you have nothing in common" from "nothing playing there".
            model.addAttribute("sharedArtistCount", sharedScanService.sharedArtistCount(scan));
        }
    }

    /**
     * Admin-only: create the pairing. The other participant comes from the allow-list dropdown.
     * htmx request -&gt; the {@code content} fragment, re-populated with the new pairing (also
     * what gives the create form's {@code hx-disabled-elt="find button"} something to swap into,
     * so a double-click's second submit lands on a form that's already gone). Non-JS fallback ->
     * a plain redirect, like {@code ShowController#scanNow}'s non-htmx branch.
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
        adminGuard.require();
        sharedScanService.create(label, currentUser.email(), ownerB);
        if (hxRequest != null) {
            populatePage(model, currentUser.email());
            model.addAttribute("justCreated", true);
            return "shared :: content";
        }
        return "redirect:/shared";
    }

    /** Either participant may set where the shared scan looks. Publishes SettingsChanged, re-duing its jobs. */
    @PostMapping("/shared/{id}/settings")
    public String updateSettings(@PathVariable Long id,
                                  @RequestParam String postalCode,
                                  @RequestParam(defaultValue = "50") int radiusMiles,
                                  @RequestParam(defaultValue = "6") int monthsAhead) {
        SharedScan scan = sharedScanService.requireVisible(currentUser.email(), id);
        sharedScanService.updateSettings(scan, postalCode, radiusMiles, monthsAhead);
        return "redirect:/shared";
    }

    /**
     * Re-check the intersection and mark this shared scan's jobs due now. There is no synchronous
     * scan to wait on -- the paced poller drains them -- so this queues and returns, exactly like
     * the Shows page's own "Scan now".
     */
    @PostMapping("/shared/{id}/scan-now")
    public String scanNow(@PathVariable Long id) {
        SharedScan scan = sharedScanService.requireVisible(currentUser.email(), id);
        reconciler.reconcile(scan);
        scanJobRepository.redueAll(scan.getOwnerKey(), Instant.now());
        return "redirect:/shared";
    }
}
