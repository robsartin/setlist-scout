package com.robsartin.setlistscout.catalog;

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

@Controller
@RequestMapping("/artists")
public class ArtistController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    /** Cap lines read from an uploaded artist file -- a guardrail against a runaway upload. */
    private static final int MAX_UPLOAD_LINES = 2000;

    private final ArtistRepository artistRepository;
    private final CurrentUser currentUser;
    private final ArtistSeedService seedService;

    public ArtistController(ArtistRepository artistRepository,
                           CurrentUser currentUser, ArtistSeedService seedService) {
        this.artistRepository = artistRepository;
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
