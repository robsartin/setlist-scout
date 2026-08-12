package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSeedService;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.expansion.ExpansionService;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/artists")
public class ArtistController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    /** Cap lines read from an uploaded artist file -- a guardrail against a runaway upload. */
    private static final int MAX_UPLOAD_LINES = 2000;

    private final ArtistRepository artistRepository;
    private final ExpansionService expansionService;
    private final CurrentUser currentUser;
    private final ArtistSeedService seedService;

    public ArtistController(ArtistRepository artistRepository, ExpansionService expansionService,
                           CurrentUser currentUser, ArtistSeedService seedService) {
        this.artistRepository = artistRepository;
        this.expansionService = expansionService;
        this.currentUser = currentUser;
        this.seedService = seedService;
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
        // A nameless seed would search Ticketmaster with keyword="" and pull back every local
        // event (issue #49); ArtistSeedService trims and skips blanks/duplicates.
        seedService.addSeedIfNew(owner, name);
        if (hxRequest != null) {
            populateActive(model, owner);
            return "artists :: activeSection";
        }
        return "redirect:/artists";
    }

    /**
     * Bulk-add seeds from an uploaded plain-text file (one artist per line). Blank lines, {@code #}
     * comments, and names that already exist are skipped ({@link ArtistSeedService}); reads at most
     * {@link #MAX_UPLOAD_LINES} lines. Owner-scoped. Redirects with a summary flash message.
     */
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        String owner = currentUser.email();
        int added = 0;
        if (file != null && !file.isEmpty()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                int seen = 0;
                while ((line = reader.readLine()) != null && seen < MAX_UPLOAD_LINES) {
                    seen++;
                    if (seedService.addSeedIfNew(owner, line)) added++;
                }
            } catch (IOException e) {
                redirect.addFlashAttribute("uploadMessage", "Could not read that file.");
                return "redirect:/artists";
            }
        }
        redirect.addFlashAttribute("uploadMessage",
                "Added " + added + " new artist" + (added == 1 ? "" : "s") + " from the file.");
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
                a.setStatus(ArtistStatus.APPROVED);
                artistRepository.save(a);
            } else if ("reject".equals(decision)) {
                a.setStatus(ArtistStatus.REJECTED);
                artistRepository.save(a);
            }
            // "later" (or missing) -> leave it PENDING_REVIEW for a future pass
        }
        return "redirect:/artists";
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
