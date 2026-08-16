package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.AdminGuard;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
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
        String email = currentUser.email();
        List<SharedScan> scans = sharedScanService.visibleTo(email);
        model.addAttribute("sharedScans", scans);
        if (!scans.isEmpty()) {
            SharedScan scan = scans.get(0);
            model.addAttribute("scan", scan);
            model.addAttribute("settings", sharedScanService.settingsFor(scan));
            model.addAttribute("shows", sharedScanService.showsFor(scan));
            model.addAttribute("otherParticipant", scan.otherParticipant(email));
            // Lets the page distinguish "you have nothing in common" from "nothing playing there".
            model.addAttribute("sharedArtistCount", sharedScanService.sharedArtistCount(scan));
        }
        return "shared";
    }

    /** Admin-only: create the pairing. The other participant comes from the allow-list dropdown. */
    @PostMapping("/shared")
    public String create(@RequestParam String label, @RequestParam String ownerB) {
        adminGuard.require();
        sharedScanService.create(label, currentUser.email(), ownerB);
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
