package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import com.robsartin.setlistscout.settings.SearchSettings;
import com.robsartin.setlistscout.settings.SettingsService;
import com.robsartin.setlistscout.shared.AdminGuard;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Controller
public class ShowController {

    /** htmx sets this header on its requests; when present we return just the changed fragment. */
    private static final String HX_REQUEST = "HX-Request";

    /** Matches shows.html's date/time rendering, for #166's outcome messages/aria-labels. */
    private static final DateTimeFormatter SHOW_LABEL_FORMAT =
            DateTimeFormatter.ofPattern("EEE, MMM d yyyy h:mm a", Locale.ENGLISH);

    private final ShowRepository showRepository;
    private final ArtistRepository artistRepository;
    private final ScanJobRepository scanJobRepository;
    private final SettingsService settingsService;
    private final CurrentUser currentUser;
    private final AdminGuard adminGuard;

    public ShowController(ShowRepository showRepository,
                           ArtistRepository artistRepository,
                           ScanJobRepository scanJobRepository,
                           SettingsService settingsService,
                           CurrentUser currentUser,
                           AdminGuard adminGuard) {
        this.showRepository = showRepository;
        this.artistRepository = artistRepository;
        this.scanJobRepository = scanJobRepository;
        this.settingsService = settingsService;
        this.currentUser = currentUser;
        this.adminGuard = adminGuard;
    }

    @GetMapping("/")
    public String shows(@RequestParam(defaultValue = "eventDate") String sort,
                        @RequestParam(defaultValue = "false") boolean showHidden, Model model) {
        String owner = currentUser.email();
        populateShows(model, owner, sort, showHidden, null);
        return "shows";
    }

    /** Loads the owner's shows (sorted, hidden-filtered per {@code showHidden}) plus their settings into the model. */
    private void populateShows(Model model, String owner, String sort, boolean showHidden, ShowActionOutcome outcome) {
        SearchSettings settings = settingsService.getOrCreateSettings(owner);
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());

        List<Show> shows = queryShows(owner, showHidden, start, end);
        shows.sort(comparatorFor(sort));

        // One findByOwner load feeds both name sets below -- the real owner's catalog is
        // 13,000+ rows, so this avoids issuing a second full-catalog-shaped query alongside it.
        List<Artist> ownerArtists = artistRepository.findByOwner(owner);

        Set<String> tributeArtistNames = ownerArtists.stream()
                .filter(a -> a.getSource() == ArtistSource.TRIBUTE_EXPANSION)
                .map(a -> a.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        // #206: TicketmasterService stores the EVENT TITLE, not a catalog artist name, in
        // artist_name (label is the event name; it falls back to the artist name only when
        // blank) -- e.g. "A Very Merry Symphony ft. Austin Symphony Orchestra" for a real show
        // the owner has today, a string that is not, and never will be, in the catalog. So this
        // active-artist check applies ONLY to venue:-sourced rows (Task 3's per-venue scan);
        // a blanket filter would hide every Ticketmaster/Bandsintown show the owner has.
        Set<String> activeArtistNames = ownerArtists.stream()
                .filter(a -> a.getStatus() == ArtistStatus.SEED || a.getStatus() == ArtistStatus.APPROVED)
                .map(a -> ArtistNameNormalizer.normalize(a.getName()))
                .collect(Collectors.toSet());

        shows = shows.stream()
                .filter(s -> !s.getSource().startsWith("venue:")
                        || activeArtistNames.contains(ArtistNameNormalizer.normalize(s.getArtistName())))
                .toList();

        long hiddenCount = showRepository.countByOwnerAndEventDateTimeBetweenAndHiddenAtIsNotNull(owner, start, end);

        model.addAttribute("shows", shows);
        model.addAttribute("currentSort", sort);
        model.addAttribute("showHidden", showHidden);
        model.addAttribute("hiddenCount", hiddenCount);
        model.addAttribute("settings", settings);
        model.addAttribute("tributeArtistNames", tributeArtistNames);

        ShowActionOutcome resolved = focusable(outcome, shows);
        if (resolved != null) {
            model.addAttribute("outcome", resolved);
        }
    }

    /** The owner's shows in the given window: every show when {@code showHidden}, otherwise only non-hidden ones (issue #166). */
    private List<Show> queryShows(String owner, boolean showHidden, LocalDateTime start, LocalDateTime end) {
        return showHidden
                ? showRepository.findByOwnerAndEventDateTimeBetweenOrderByEventDateTimeAsc(owner, start, end)
                : showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(owner, start, end);
    }

    private static Comparator<Show> comparatorFor(String sort) {
        return switch (sort) {
            case "discoveredAt" -> Comparator.comparing(Show::getDiscoveredAt);
            case "artist" -> Comparator.comparing(Show::getArtistName, String.CASE_INSENSITIVE_ORDER);
            case "venue" -> Comparator.comparing(Show::getVenueName, String.CASE_INSENSITIVE_ORDER);
            case "price" -> Comparator.comparing(Show::getPrice, Comparator.nullsLast(Comparator.naturalOrder()));
            default -> Comparator.comparing(Show::getEventDateTime);
        };
    }

    /**
     * Manually request a scan: mark all of this owner's scan jobs due-now (the paced poller picks
     * them up within a tick) and confirm. There's no synchronous scan to wait on in the per-unit
     * model, so this just queues -- newly found shows appear on later page loads as the poller drains.
     */
    @PostMapping("/scan-now")
    public String scanNow(@RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                          @RequestParam(defaultValue = "eventDate") String sort,
                          @RequestParam(defaultValue = "false") boolean showHidden, Model model) {
        String owner = currentUser.email();
        scanJobRepository.redueAll(owner, Instant.now());
        if (hxRequest != null) {
            populateShows(model, owner, sort, showHidden, ShowActionOutcome.anchor(
                    "Scan queued. Newly found shows will appear here as they're picked up."));
            model.addAttribute("scanQueued", true);
            return "shows :: showsRegion";
        }
        return "redirect:/";
    }

    /**
     * Admin-only cross-account escape hatch (#136): re-due a DIFFERENT owner's scan jobs, e.g.
     * when their scheduled poller hasn't ticked yet and they can't sign in themselves right now.
     * Reuses the exact same redueAll mechanism as the self-service {@link #scanNow} above, just
     * parameterized by {@code targetOwner} instead of {@code currentUser.email()}. Gated by
     * {@link com.robsartin.setlistscout.shared.AdminGuard#require()} -- see its Javadoc for why
     * this is a config check, not a roles system. The admin's OWN shows page (not the target
     * owner's) is what gets re-rendered on an htmx request, since it's the admin's browser that
     * made the call.
     */
    @PostMapping("/admin/scan-now")
    public String adminScanNow(@RequestParam String targetOwner,
                               @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                               @RequestParam(defaultValue = "eventDate") String sort,
                               @RequestParam(defaultValue = "false") boolean showHidden, Model model) {
        adminGuard.require();
        scanJobRepository.redueAll(targetOwner, Instant.now());
        String owner = currentUser.email();
        if (hxRequest != null) {
            populateShows(model, owner, sort, showHidden, ShowActionOutcome.anchor("Scan queued for " + targetOwner + "."));
            model.addAttribute("scanQueued", true);
            model.addAttribute("scanQueuedFor", targetOwner);
            return "shows :: showsRegion";
        }
        return "redirect:/";
    }

    /**
     * Hide one show from the default Shows list (issue #166). Reversible via {@link #unhideShow}
     * and the "show hidden" toggle -- never a hard delete. Owner-scoped via
     * {@code findByIdAndOwner}: a foreign show id is a silent no-op, not a leak, matching this
     * codebase's established pattern (e.g. {@code ArtistActivationService#changeStatus}).
     */
    @PostMapping("/shows/{id}/hide")
    public String hideShow(@PathVariable Long id,
                           @RequestParam(defaultValue = "eventDate") String sort,
                           @RequestParam(defaultValue = "false") boolean showHidden,
                           @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                           Model model) {
        return toggleHidden(id, true, sort, showHidden, hxRequest, model);
    }

    /** Move a hidden show back into the default list. Owner-scoped the same way as {@link #hideShow}. */
    @PostMapping("/shows/{id}/unhide")
    public String unhideShow(@PathVariable Long id,
                             @RequestParam(defaultValue = "eventDate") String sort,
                             @RequestParam(defaultValue = "false") boolean showHidden,
                             @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                             Model model) {
        return toggleHidden(id, false, sort, showHidden, hxRequest, model);
    }

    /**
     * The shared hide/unhide path (issue #166, mirroring #155's shape). Focus resolution differs
     * by whether the acted-on row is about to disappear from the response:
     * <ul>
     *   <li>{@code showHidden} true: hidden or not, the row stays rendered (it just flips which of
     *   Hide/Unhide it offers) -- refocus that same row directly.</li>
     *   <li>{@code showHidden} false: only Hide is ever reachable here (Unhide only renders when
     *   hidden rows are visible, i.e. {@code showHidden} true), and hiding removes the row from
     *   this exact list -- so focus its successor, resolved from the list AS IT STANDS before the
     *   mutation, per {@link ShowActionOutcome#afterRow}'s rule.</li>
     * </ul>
     */
    private String toggleHidden(Long id, boolean hide, String sort, boolean showHidden,
                                String hxRequest, Model model) {
        String owner = currentUser.email();
        Show acted = showRepository.findByIdAndOwner(id, owner).orElse(null);
        if (acted == null) {
            return actionResult(hxRequest, model, owner, sort, showHidden, ShowActionOutcome.anchor(null));
        }
        String message = (hide ? "Hid " : "Unhid ") + describeShow(acted) + ".";
        ShowActionOutcome outcome = showHidden
                ? ShowActionOutcome.row(id, message)
                : ShowActionOutcome.afterRow(resolveVisibleShows(owner, sort), id, message);
        acted.setHiddenAt(hide ? Instant.now() : null);
        showRepository.save(acted);
        return actionResult(hxRequest, model, owner, sort, showHidden, outcome);
    }

    /** The default (non-hidden) list in current render order -- used to resolve the post-hide focus successor before the acted-on row is mutated out of it. */
    private List<Show> resolveVisibleShows(String owner, String sort) {
        SearchSettings settings = settingsService.getOrCreateSettings(owner);
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());
        List<Show> shows = showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(owner, start, end);
        shows.sort(comparatorFor(sort));
        return shows;
    }

    private static String describeShow(Show show) {
        return show.getArtistName() + " at " + show.getVenueName() + " on " + SHOW_LABEL_FORMAT.format(show.getEventDateTime());
    }

    /**
     * Downgrades a ROW focus target that won't be in the response to the region anchor -- the
     * same safety net {@code ReviewController#focusable} applies for Candidates (issue #155): if
     * the id this outcome names isn't among the rows about to render, an {@code autofocus}
     * pointing at nothing would silently drop focus to {@code <body>} again.
     */
    private static ShowActionOutcome focusable(ShowActionOutcome outcome, List<Show> shows) {
        if (outcome == null) {
            return null;
        }
        if (outcome.focus() == ShowActionOutcome.Focus.ROW
                && shows.stream().noneMatch(s -> outcome.showId().equals(s.getId()))) {
            return outcome.downgradedToAnchor();
        }
        return outcome;
    }

    /**
     * Shared response for hide/unhide (issue #166): htmx request -&gt; the {@code showsRegion}
     * fragment, re-populated with the resolved {@code outcome} so focus/announcement land
     * correctly. Non-JS fallback -&gt; a plain redirect back to the Shows page (like
     * {@link #scanNow}'s non-htmx branch, this resets {@code sort}/{@code showHidden} to their
     * defaults on that rarely-exercised path rather than threading them through the redirect --
     * htmx itself requires JS, so a genuinely no-JS client already can't have set a non-default
     * toggle/sort in the first place).
     */
    private String actionResult(String hxRequest, Model model, String owner, String sort,
                                boolean showHidden, ShowActionOutcome outcome) {
        if (hxRequest != null) {
            populateShows(model, owner, sort, showHidden, outcome);
            return "shows :: showsRegion";
        }
        return "redirect:/";
    }
}
