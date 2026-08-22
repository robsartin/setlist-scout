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
    /**
     * Issue #174: the Next/Previous pagination links use {@code hx-push-url="true"} (so the cursor
     * is bookmark/back-button friendly, same as candidates.html's {@code via} sidebar links), which
     * means a Back navigation that misses htmx's local history cache re-fetches this URL with BOTH
     * headers set. That response gets swapped into {@code <body>} client-side, so it needs the FULL
     * page, not the bare fragment -- see {@code ReviewController#candidates}'s identical handling.
     */
    private static final String HX_HISTORY_RESTORE_REQUEST = "HX-History-Restore-Request";
    /** Fragment view name shared by every bare-fragment htmx response this controller returns. */
    private static final String ACTIVE_SECTION_FRAGMENT = "artists :: activeSection";

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
    private final ArtistPager artistPager;

    public ArtistController(ArtistRepository artistRepository, ArtistEdgeRepository artistEdgeRepository,
                           CurrentUser currentUser, ArtistSeedService seedService,
                           ArtistActivationService activationService, ArtistConnectionsService connectionsService,
                           ArtistImportService importService, ArtistImportRepository artistImportRepository,
                           ArtistPager artistPager) {
        this.artistRepository = artistRepository;
        this.artistEdgeRepository = artistEdgeRepository;
        this.currentUser = currentUser;
        this.seedService = seedService;
        this.activationService = activationService;
        this.connectionsService = connectionsService;
        this.importService = importService;
        this.artistImportRepository = artistImportRepository;
        this.artistPager = artistPager;
    }

    /**
     * Issue #174: {@code after}/{@code before} are the keyset cursors a Next/Previous link sends
     * back (see {@code artists.html}'s {@code activeSection} fragment); neither present is page 1.
     * {@code fragment} decides both whether the response is the bare {@code activeSection} (a
     * genuine htmx swap) and whether {@link #populateActive} emits the out-of-band {@code
     * #sr-status} announcement -- a history-restore re-fetch must render neither, or the layout's
     * real {@code #sr-status} node would collide with a duplicate id.
     */
    @GetMapping
    public String list(@RequestParam(required = false) String after,
                       @RequestParam(required = false) String before,
                       @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                       @RequestHeader(value = HX_HISTORY_RESTORE_REQUEST, required = false) String historyRestore,
                       Model model) {
        String owner = currentUser.email();
        boolean fragment = hxRequest != null && historyRestore == null;
        populateActive(model, owner, after, before, fragment);
        return fragment ? ACTIVE_SECTION_FRAGMENT : "artists";
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
            // Issue #174: lands on the FIRST page, not wherever the (now stale, one row longer)
            // list's cursor used to point. Chosen over the alternatives the issue calls out --
            // computing which page the new row landed on (an extra query, and a boundary that the
            // very next insert could shift anyway) or preserving the incoming cursor position
            // (nothing on THIS request even carries one; the add form sits outside #active-section,
            // issue #175) -- because it's the simplest option that's still correct, and the add
            // form plus the import-progress display both live at the top of the page already, so
            // returning there keeps the confirmation in view next to what the user just did. The
            // same reasoning is applied uniformly to setSiteUrl/removeFromSeed below rather than
            // inventing a third, untested behaviour for those.
            populateActive(model, owner, null, null, true);
            return ACTIVE_SECTION_FRAGMENT;
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
            // Issue #174: first page, same reasoning as addSeed's comment above.
            populateActive(model, owner, null, null, true);
            return ACTIVE_SECTION_FRAGMENT;
        }
        return "redirect:/artists";
    }

    /**
     * Set or clear an artist's default venue name/city (issue #218): the band-site scan path
     * ({@code BandSiteShowSource}) applies this only when a scraped show has no venue of its own --
     * e.g. Austin Symphony Orchestra's own season page names no hall anywhere. Both fields travel
     * together and are blanked together; a name with no city would leave a defaulted show's city
     * null, which the distance filter treats as "no match" and drops (issue #211's shape). Owner-scoped.
     */
    @PostMapping("/{id}/default-venue")
    public String setDefaultVenue(@PathVariable Long id,
                             @RequestParam String name,
                             @RequestParam String city,
                             @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                             Model model) {
        String owner = currentUser.email();
        artistRepository.findByIdAndOwner(id, owner).ifPresent(a -> {
            a.setDefaultVenueName(name.isBlank() ? null : name.trim());
            a.setDefaultVenueCity(city.isBlank() ? null : city.trim());
            artistRepository.save(a);
        });
        if (hxRequest != null) {
            // Issue #174: first page, same reasoning as addSeed's comment above.
            populateActive(model, owner, null, null, true);
            return ACTIVE_SECTION_FRAGMENT;
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
            // Issue #174: first page, same reasoning as addSeed's comment above.
            populateActive(model, owner, null, null, true);
            return ACTIVE_SECTION_FRAGMENT;
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

    /**
     * Builds the {@code activeSection} model: the current keyset page (issue #174, via {@link
     * ArtistPager}) plus the upload-progress attributes, for whichever of this controller's four
     * action handlers got here (list, addSeed, setSiteUrl, removeFromSeed).
     * <p>
     * {@code after}/{@code before} are the REQUEST's own cursors -- non-null only when {@link
     * #list} is answering a Next/Previous click; every mutating action calls this with both {@code
     * null} (see {@link #addSeed}'s comment for why landing on the first page, uniformly, is the
     * chosen behaviour there and not just the path of least resistance).
     * <p>
     * {@code announce} gates the out-of-band {@code #sr-status} paragraph {@code artists.html}
     * renders inside {@code activeSection} (issue #174's "announce page changes" requirement,
     * mirroring candidates.html's identical {@code #sr-status} convention): {@code true} for every
     * genuine fragment swap, {@code false} for a full-page render, where the layout already carries
     * the one real {@code #sr-status} node and a second copy under the same id would be a
     * duplicate-id bug -- see {@link #list}'s {@code fragment} computation.
     */
    private void populateActive(Model model, String owner, String after, String before, boolean announce) {
        ActivePage page = artistPager.page(owner, after, before);
        model.addAttribute("active", page.artists());
        model.addAttribute("activePage", page);
        model.addAttribute("announceActivePage", announce);
        // #177 upload-progress display: plain server-rendered counts, refreshed whenever this
        // model gets built (a full page load, or any htmx action that swaps activeSection) --
        // no polling, no JavaScript, matching the rest of this app.
        model.addAttribute("importPendingCount",
                artistImportRepository.countByOwnerAndStatus(owner, ArtistImportStatus.PENDING));
        model.addAttribute("importFailed",
                artistImportRepository.findByOwnerAndStatusOrderByNameAsc(owner, ArtistImportStatus.FAILED));
    }
}
