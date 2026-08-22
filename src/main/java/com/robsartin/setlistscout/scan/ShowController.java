package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistActivationService;
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

    /**
     * The three {@code ShowActionOutcome#action} tags this page's rows can carry (issue #223) --
     * shared constants so the controller and {@code shows.html}'s three button predicates can't
     * drift apart on the literal string.
     */
    private static final String ACTION_HIDE = "hide";
    private static final String ACTION_UNHIDE = "unhide";
    private static final String ACTION_HIDE_AND_CANCEL = "hide-and-cancel";

    private final ShowRepository showRepository;
    private final ArtistRepository artistRepository;
    private final ScanJobRepository scanJobRepository;
    private final SettingsService settingsService;
    private final CurrentUser currentUser;
    private final AdminGuard adminGuard;
    private final ArtistActivationService activationService;

    public ShowController(ShowRepository showRepository,
                           ArtistRepository artistRepository,
                           ScanJobRepository scanJobRepository,
                           SettingsService settingsService,
                           CurrentUser currentUser,
                           AdminGuard adminGuard,
                           ArtistActivationService activationService) {
        this.showRepository = showRepository;
        this.artistRepository = artistRepository;
        this.scanJobRepository = scanJobRepository;
        this.settingsService = settingsService;
        this.currentUser = currentUser;
        this.adminGuard = adminGuard;
        this.activationService = activationService;
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

        // Two narrow, DB-filtered queries -- NOT one shared findByOwner load. An earlier version
        // of this method consolidated onto a single findByOwner(owner) call on the mistaken belief
        // that tributeArtistNames already loaded the full catalog; it did not (it was always this
        // same narrow findByOwnerAndSource query), so that "consolidation" actually introduced a
        // full-catalog load -- 13,000+ Artist entities into the persistence context on every render
        // and every htmx hide/unhide/scan-now -- where none had existed before (#206 fix round 1,
        // Important 2). tributeArtistNames and activeArtistNames need different ROWS (different
        // source vs. status predicates), so they genuinely cannot share one query; two queries each
        // filtered at the database is strictly cheaper than one query that isn't filtered at all.
        Set<String> tributeArtistNames = artistRepository.findByOwnerAndSource(owner, ArtistSource.TRIBUTE_EXPANSION)
                .stream()
                .map(a -> a.getName().toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        Set<String> activeArtistNames = activeArtistNames(owner);
        shows = visibleToOwner(shows, activeArtistNames);

        // #220 (owner decision recorded in the brief): hiddenCount means "shows you hid that you
        // could still see" -- the same venue-follow filter as `shows` above, applied to the hidden
        // rows, so the "show hidden" toggle always reveals exactly the number it promised. Needs
        // the actual rows, not a database COUNT, because the filter is an in-memory
        // normalized-name comparison -- see visibleToOwner's Javadoc.
        List<Show> hiddenShows = showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNotNull(owner, start, end);
        long hiddenCount = visibleToOwner(hiddenShows, activeArtistNames).size();

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

    /**
     * The owner's active ({@code SEED} or {@code APPROVED}) artist names, normalized -- the set
     * {@link #visibleToOwner} tests a {@code venue:}-sourced show's performer against. Issued as
     * its own narrow, status-filtered query every place it's needed (currently {@link
     * #populateShows} and {@link #resolveVisibleShows}) rather than threading one instance
     * through both call paths -- matches the two-narrow-queries rule documented on {@code
     * populateShows}'s {@code tributeArtistNames}/{@code activeArtistNames} block: never widen
     * this to a plain {@code findByOwner}.
     */
    private Set<String> activeArtistNames(String owner) {
        return artistRepository.findByOwnerAndStatusIn(owner, List.of(ArtistStatus.SEED, ArtistStatus.APPROVED))
                .stream()
                .map(a -> ArtistNameNormalizer.normalize(a.getName()))
                .collect(Collectors.toSet());
    }

    /**
     * The venue-follow filter (#206, extracted for #220): drops a {@code venue:}-sourced show
     * whose performer isn't among {@code activeArtistNames}. Applies ONLY to venue:-prefixed
     * sources -- {@code TicketmasterService} stores the EVENT TITLE, not a catalog artist name,
     * in {@code artistName} (label is the event name; it falls back to the artist name only when
     * blank) -- e.g. "A Very Merry Symphony ft. Austin Symphony Orchestra" for a real show the
     * owner has today, a string that is not, and never will be, in the catalog. A blanket filter
     * would hide every Ticketmaster/Bandsintown show the owner has.
     * <p>
     * One method for every place this rule applies -- the display list, {@code hiddenCount}
     * (both in {@link #populateShows}), and {@link #resolveVisibleShows}'s focus-successor search
     * -- so they can't drift apart on it again. That drift is #220's whole reason for existing:
     * #206 introduced this filter in exactly one of the three places, and nothing caught the
     * other two.
     */
    private static List<Show> visibleToOwner(List<Show> shows, Set<String> activeArtistNames) {
        return shows.stream()
                .filter(s -> !s.getSource().startsWith("venue:")
                        || activeArtistNames.contains(ArtistNameNormalizer.normalize(s.getArtistName())))
                .toList();
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
     * The row-focus {@code action} tag (issue #223) is derived from the row's POST-mutation hidden
     * state, not from which endpoint was called: a row that ends up hidden only ever renders its
     * Unhide button, whichever of hide/hide-and-cancel put it there.
     */
    private String toggleHidden(Long id, boolean hide, String sort, boolean showHidden,
                                String hxRequest, Model model) {
        String owner = currentUser.email();
        Show acted = showRepository.findByIdAndOwner(id, owner).orElse(null);
        if (acted == null) {
            return actionResult(hxRequest, model, owner, sort, showHidden, ShowActionOutcome.anchor(null));
        }
        String message = (hide ? "Hid " : "Unhid ") + describeShow(acted) + ".";
        String rowAction = hide ? ACTION_UNHIDE : ACTION_HIDE;
        ShowActionOutcome outcome = showHidden
                ? ShowActionOutcome.row(id, rowAction, message)
                : ShowActionOutcome.afterRow(resolveVisibleShows(owner, sort), id, ACTION_HIDE, message);
        acted.setHiddenAt(hide ? Instant.now() : null);
        showRepository.save(acted);
        return actionResult(hxRequest, model, owner, sort, showHidden, outcome);
    }

    /**
     * Hide a show AND transition the artist behind it in one request (issue #223) -- the owner is
     * done with this show, and with the artist that's showing it up.
     * <ul>
     *   <li>{@code SEED} -&gt; {@code REMOVED}: a hand-curated seed the owner no longer wants
     *   tracked, deliberately kept out of the rejected-candidate queue.</li>
     *   <li>Anything else active ({@code PENDING_REVIEW}, or {@code APPROVED} -- which, in this
     *   app, only ever got there via expansion review) -&gt; {@code REJECTED}, so {@code
     *   catalog.VenuePerformerListener}'s {@code ON CONFLICT DO NOTHING} keeps it from being
     *   re-suggested by the next venue scan.</li>
     *   <li>Already {@code REMOVED} stays {@code REMOVED} -- never bumped to {@code REJECTED},
     *   which would clutter the rejected-candidate queue with something the owner already
     *   handled via the seed-removal path. (An artist already {@code REJECTED} mapping back to
     *   {@code REJECTED} is a harmless no-op, so it needs no such guard.)</li>
     * </ul>
     * Status changes go through {@link ArtistActivationService#changeStatus}, never a direct
     * repository save, so the domain events fire and {@code ScanJobListener}/{@code
     * ExpandJobListener} cancel the artist's jobs (CLAUDE.md rule; {@code scan} writing {@code
     * catalog}'s aggregate directly would also fail {@code ModularityTests}).
     * <p>
     * A null {@code artist_id} (a row that predates #223, or a venue-sourced row whose performer
     * isn't a resolved catalog artist yet) still hides the show; nothing else happens. Owner-scoped
     * twice over: {@code findByIdAndOwner} for the show, {@code findByIdAndOwner} again for the
     * artist -- a foreign owner's show or artist is a silent no-op, not a leak.
     */
    @PostMapping("/shows/{id}/hide-and-cancel")
    public String hideAndCancel(@PathVariable Long id,
                                @RequestParam(defaultValue = "eventDate") String sort,
                                @RequestParam(defaultValue = "false") boolean showHidden,
                                @RequestHeader(value = HX_REQUEST, required = false) String hxRequest,
                                Model model) {
        String owner = currentUser.email();
        Show acted = showRepository.findByIdAndOwner(id, owner).orElse(null);
        if (acted == null) {
            return actionResult(hxRequest, model, owner, sort, showHidden, ShowActionOutcome.anchor(null));
        }
        Artist artist = acted.getArtistId() == null
                ? null
                : artistRepository.findByIdAndOwner(acted.getArtistId(), owner).orElse(null);
        String message = artist == null
                ? "Hid " + describeShow(acted) + "."
                : "Hid " + describeShow(acted) + " and stopped following " + artist.getName() + ".";
        ShowActionOutcome outcome = showHidden
                ? ShowActionOutcome.row(id, ACTION_UNHIDE, message)
                : ShowActionOutcome.afterRow(resolveVisibleShows(owner, sort), id, ACTION_HIDE_AND_CANCEL, message);

        acted.setHiddenAt(Instant.now());
        showRepository.save(acted);
        if (artist != null) {
            activationService.changeStatus(artist.getId(), owner, targetStatusFor(artist.getStatus()));
        }
        return actionResult(hxRequest, model, owner, sort, showHidden, outcome);
    }

    /** The status-transition rule {@link #hideAndCancel} applies -- see its own javadoc for the rationale. */
    private static ArtistStatus targetStatusFor(ArtistStatus current) {
        if (current == ArtistStatus.SEED) {
            return ArtistStatus.REMOVED;
        }
        if (current == ArtistStatus.REMOVED) {
            return ArtistStatus.REMOVED;
        }
        return ArtistStatus.REJECTED;
    }

    /**
     * The default (non-hidden) list in current render order -- used to resolve the post-hide focus
     * successor before the acted-on row is mutated out of it. Applies the same venue-follow filter
     * as {@link #populateShows} (issue #220 Finding 2) via {@link #visibleToOwner}, so a row the
     * filter would remove from the rendered page is never offered as the successor -- before this
     * fix, {@link #focusable} still caught the bad pick and degraded to the region anchor (no
     * keyboard trap), but that discarded a real, visible successor further down the list.
     */
    private List<Show> resolveVisibleShows(String owner, String sort) {
        SearchSettings settings = settingsService.getOrCreateSettings(owner);
        LocalDateTime start = LocalDateTime.now();
        LocalDateTime end = start.plusMonths(settings.getMonthsAhead());
        List<Show> shows = showRepository.findByOwnerAndEventDateTimeBetweenAndHiddenAtIsNullOrderByEventDateTimeAsc(owner, start, end);
        shows.sort(comparatorFor(sort));
        return visibleToOwner(shows, activeArtistNames(owner));
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
