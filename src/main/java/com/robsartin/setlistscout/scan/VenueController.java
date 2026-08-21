package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The {@code /venues} management page (#206 Task 6) -- the only way an owner adds a venue outside
 * a test, and therefore what makes the whole follow-a-venue feature reachable. Mirrors {@code
 * catalog.ArtistController}'s shape (add form above the list, real {@code <label>}s) but not its
 * htmx fragment-swap machinery: none of this page's actions need a partial update (the list is
 * small, unpaginated, and the add form sits outside it), so a plain POST-redirect-GET keeps this
 * page's behavior fully covered by the given tests without inventing an htmx contract nothing here
 * needs -- see this task's report for the full reasoning.
 */
@Controller
@RequestMapping("/venues")
public class VenueController {

    private final VenueRepository venueRepository;
    private final VenueScanJobRepository venueScanJobRepository;
    private final ShowRepository showRepository;
    private final VenueService venueService;
    private final CurrentUser currentUser;

    public VenueController(VenueRepository venueRepository, VenueScanJobRepository venueScanJobRepository,
                            ShowRepository showRepository, VenueService venueService, CurrentUser currentUser) {
        this.venueRepository = venueRepository;
        this.venueScanJobRepository = venueScanJobRepository;
        this.showRepository = showRepository;
        this.venueService = venueService;
        this.currentUser = currentUser;
    }

    @GetMapping
    public String list(Model model) {
        model.addAttribute("venues", rows(currentUser.email()));
        return "venues";
    }

    /**
     * Adds a venue (and its scan job -- see {@link VenueService#addVenue}) and returns to the
     * list. Plain redirect, not an htmx fragment response: matches {@code
     * ArtistController#upload}'s equivalent handler, and this page's add form has no fragment
     * contract to honor (see the class javadoc).
     */
    @PostMapping
    public String add(@RequestParam String name, @RequestParam String url) {
        venueService.addVenue(currentUser.email(), name, url);
        return "redirect:/venues";
    }

    /**
     * Builds one {@link VenueRow} per owner-scoped venue: its own scan job's {@code lastRunAt}
     * (null if the job has never run) and its own contributed-show count, each looked up from a
     * single owner-wide load rather than one query per venue. The show count is matched to its
     * venue by recomputing that venue's OWN {@code "venue:" + host(calendarUrl)} key -- the exact
     * value {@code VenueScanRunner#persist} stores shows under -- and looking it up in one {@code
     * COUNT(*) ... GROUP BY} result, never {@code findAll().size()}.
     */
    private List<VenueRow> rows(String owner) {
        List<Venue> venues = venueRepository.findByOwnerOrderByNameAsc(owner);

        Map<Long, VenueScanJob> jobsByVenueId = new HashMap<>();
        for (VenueScanJob job : venueScanJobRepository.findByOwner(owner)) {
            jobsByVenueId.put(job.getVenueId(), job);
        }

        Map<String, Long> showCountsBySource = new HashMap<>();
        for (VenueSourceCount count : showRepository.countByOwnerGroupedByVenueSource(owner)) {
            showCountsBySource.put(count.getSource(), count.getShowCount());
        }

        return venues.stream()
                .map(venue -> {
                    VenueScanJob job = jobsByVenueId.get(venue.getId());
                    Instant lastScanned = job == null ? null : job.getLastRunAt();
                    long showCount = showCountsBySource.getOrDefault(sourceFor(venue.getCalendarUrl()), 0L);
                    return new VenueRow(venue, lastScanned, showCount);
                })
                .toList();
    }

    /**
     * Recomputes a venue's own show-source key. Deliberately duplicated from {@code
     * VenueScanRunner}'s private {@code host} helper rather than sharing it -- see this task's
     * report for why: it is a 3-line {@code URI.getHost()} wrapper with an "unknown" fallback, not
     * a business rule like {@code ArtistNameNormalizer}, and re-touching Task 3's already-tested,
     * already-committed runner for this was judged higher risk than one small, easily-verified
     * duplicate. If this ever drifts from {@code VenueScanRunner#host}, every venue's show count
     * would read zero despite real persisted shows -- {@code showsScanHealth} and {@code
     * showCountsAreNotConflatedAcrossVenues} in {@code VenuePageRenderTest} both pin this against
     * a real Postgres-backed {@code VenueScanRunner}-style source string, so that drift cannot
     * pass silently.
     */
    private static String sourceFor(String calendarUrl) {
        try {
            return "venue:" + URI.create(calendarUrl).getHost();
        } catch (RuntimeException e) {
            return "venue:unknown";
        }
    }
}
