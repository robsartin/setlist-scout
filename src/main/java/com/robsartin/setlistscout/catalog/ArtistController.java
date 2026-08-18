package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
@RequestMapping("/artists")
public class ArtistController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    /** Hop count for the graph-validation page's reachable-set query (issue #111). */
    private static final int GRAPH_MAX_DEPTH = 2;

    private final ArtistRepository artistRepository;
    private final ArtistEdgeRepository artistEdgeRepository;
    private final CurrentUser currentUser;
    private final ArtistSeedService seedService;
    private final ArtistActivationService activationService;
    private final ArtistConnectionsService connectionsService;
    private final ArtistImportService importService;
    private final ArtistImportRepository artistImportRepository;

    public ArtistController(ArtistRepository artistRepository, ArtistEdgeRepository artistEdgeRepository,
                           CurrentUser currentUser, ArtistSeedService seedService,
                           ArtistActivationService activationService, ArtistConnectionsService connectionsService,
                           ArtistImportService importService, ArtistImportRepository artistImportRepository) {
        this.artistRepository = artistRepository;
        this.artistEdgeRepository = artistEdgeRepository;
        this.currentUser = currentUser;
        this.seedService = seedService;
        this.activationService = activationService;
        this.connectionsService = connectionsService;
        this.importService = importService;
        this.artistImportRepository = artistImportRepository;
    }

    @GetMapping
    public String list(Model model) {
        String owner = currentUser.email();
        populateActive(model, owner);
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
     * Bulk-QUEUE names from an uploaded plain-text file (one artist per line) via {@link
     * ArtistImportService#queue} -- issue #177. The request returns as soon as the names are
     * queued; {@code ArtistImportPoller} seeds them in the background, off this request thread.
     * That fixes a real 1,138-name upload that 502'd: the old version of this handler called
     * {@code ArtistSeedService#addSeedIfNew} synchronously per line inside the HTTP request, which
     * took long enough that Render's free-tier idle spin-down killed the request part-way through,
     * having imported only 79 names. Owner-scoped. Redirects with a flash message reporting how
     * many names were QUEUED, not how many were added -- most of the work hasn't happened yet when
     * this method returns.
     */
    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes redirect) {
        String owner = currentUser.email();
        int queued = 0;
        if (file != null && !file.isEmpty()) {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8))) {
                queued = importService.queue(owner, reader);
            } catch (IOException e) {
                redirect.addFlashAttribute("uploadMessage", "Could not read that file.");
                return "redirect:/artists";
            }
        }
        redirect.addFlashAttribute("uploadMessage",
                "Queued " + queued + " name" + (queued == 1 ? "" : "s") + ". They'll be added in the background.");
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
     * Take a hand-curated {@code SEED} artist off the owner's active list (issue #117). Distinct
     * from {@code ReviewController.remove}'s {@code REJECTED} transition -- see the design
     * decision at the top of {@code docs/superpowers/plans/2026-08-14-remove-from-seed-list.md}
     * for why REMOVED exists as its own terminal status rather than reusing REJECTED. Goes through
     * {@link ArtistActivationService#changeStatus}, never a direct repository save, so {@code
     * ArtistDeactivated} fires and the existing listener cancels the artist's scan_job/expand_job
     * rows. Owner-scoped for free via {@code changeStatus}'s {@code findByIdAndOwner} no-op-if-
     * absent behavior -- a foreign owner's id is silently ignored, not a leak.
     */
    @PostMapping("/{id}/remove-from-seed")
    public String removeFromSeed(@PathVariable Long id,
                                 @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                 Model model) {
        String owner = currentUser.email();
        activationService.changeStatus(id, owner, ArtistStatus.REMOVED);
        if (hxRequest != null) {
            populateActive(model, owner);
            return "artists :: activeSection";
        }
        return "redirect:/artists";
    }

    /**
     * Read-only artist-graph validation tool (issue #111): incoming + outgoing edge history and
     * the 2-hop reachable set for one owner-scoped artist. Built to validate the graph model
     * landed in #109 against real data before building a feature on it -- not a polished feature
     * page. No writes; a foreign id (another owner's artist) 404s rather than leaking existence.
     */
    @GetMapping("/{id}/graph")
    public String graph(@PathVariable Long id, Model model) {
        String owner = currentUser.email();
        Artist artist = artistRepository.findByIdAndOwner(id, owner)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        List<ArtistEdge> outgoingEdges = artistEdgeRepository.findByOwnerAndFromArtistId(owner, id);
        List<ArtistEdge> incomingEdges = artistEdgeRepository.findByOwnerAndToArtistId(owner, id);
        List<ReachableArtist> reachableRows = artistEdgeRepository.reachableWithin(owner, id, GRAPH_MAX_DEPTH);

        Set<Long> otherIds = new HashSet<>();
        outgoingEdges.forEach(e -> otherIds.add(e.getToArtistId()));
        incomingEdges.forEach(e -> otherIds.add(e.getFromArtistId()));
        reachableRows.forEach(r -> otherIds.add(r.getArtistId()));
        Map<Long, String> namesById = artistRepository.findByOwnerAndIdIn(owner, otherIds).stream()
                .collect(Collectors.toMap(Artist::getId, Artist::getName));

        List<ArtistEdgeView> outgoing = outgoingEdges.stream()
                .map(e -> new ArtistEdgeView(nameOf(namesById, e.getToArtistId()), e.getType(), e.getSource(),
                        e.getNote(), e.getCreatedAt()))
                .toList();
        List<ArtistEdgeView> incoming = incomingEdges.stream()
                .map(e -> new ArtistEdgeView(nameOf(namesById, e.getFromArtistId()), e.getType(), e.getSource(),
                        e.getNote(), e.getCreatedAt()))
                .toList();
        List<ReachableArtistView> reachable = reachableRows.stream()
                .map(r -> new ReachableArtistView(nameOf(namesById, r.getArtistId()), r.getDepth()))
                .sorted(Comparator.comparingInt(ReachableArtistView::depth).thenComparing(ReachableArtistView::name))
                .toList();

        model.addAttribute("artist", artist);
        model.addAttribute("outgoing", outgoing);
        model.addAttribute("incoming", incoming);
        model.addAttribute("reachable", reachable);
        return "artist-graph";
    }

    private static String nameOf(Map<Long, String> namesById, Long id) {
        return namesById.getOrDefault(id, "(unknown artist #" + id + ")");
    }

    /**
     * The Connections page (issue #112, graph phase 3): an AGGREGATE 2-hop traversal of the
     * artist_edge graph starting from every one of the owner's active artists at once, unlike
     * {@link #graph}'s single-artist debug view -- surfacing PENDING_REVIEW artists reachable
     * that way as genuinely new discovery material, grouped by which seed(s)/path(s) reach them
     * with each connecting edge's type/source shown for transparency. See {@link
     * ArtistConnectionsService} for the traversal approach and exactly what "genuinely new"
     * means here. Read-only: no write actions on this page.
     */
    @GetMapping("/connections")
    public String connections(Model model) {
        model.addAttribute("discovered", connectionsService.discoverConnections(currentUser.email()));
        return "artist-connections";
    }

    private void populateActive(Model model, String owner) {
        model.addAttribute("active", artistRepository.findByOwnerAndStatusIn(
                owner, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED)));
        // #177 upload-progress display: plain server-rendered counts, refreshed whenever this
        // model gets built (a full page load, or any htmx action that swaps activeSection) --
        // no polling, no JavaScript, matching the rest of this app.
        model.addAttribute("importPendingCount",
                artistImportRepository.countByOwnerAndStatus(owner, ArtistImportStatus.PENDING));
        model.addAttribute("importFailed",
                artistImportRepository.findByOwnerAndStatusOrderByNameAsc(owner, ArtistImportStatus.FAILED));
    }
}
