package com.robsartin.setlistscout.catalog;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Assembles one {@link ActivePage} of an owner's active (SEED/APPROVED) list from {@link
 * ArtistRepository}'s keyset queries (issue #174) -- kept out of {@code ArtistController} for the
 * same reason {@link ArtistConnectionsService} holds the Connections page's traversal logic: it's
 * real logic worth naming and unit-testing on its own, not a one-line repository delegation.
 *
 * <h2>Direction dispatch</h2>
 * {@link #page} takes the raw {@code after}/{@code before} request parameters and picks one of
 * three repository queries: no cursor means page 1 ({@link ArtistRepository#findActiveFirstPage}),
 * {@code after} means the next page ({@link ArtistRepository#findActiveAfter}), {@code before}
 * means the previous page ({@link ArtistRepository#findActiveBefore}, reversed for display). If a
 * caller somehow supplies both -- never from a link this app renders, only a hand-edited URL --
 * {@code after} wins; it's the more common direction and picking one deterministically beats
 * rejecting the request.
 *
 * <h2>hasNext / hasPrevious without a COUNT query</h2>
 * Every repository call asks for {@code pageSize + 1} rows. Getting back the extra row means there
 * is at least one more beyond what this page shows, in the direction just queried -- so that
 * boolean costs nothing beyond the LIMIT already needed for the page itself.
 * <p>
 * The OPPOSITE direction's boolean is not computed by any query at all: reaching the {@code after}
 * branch means the caller already holds a cursor this class only ever hands out as a rendered
 * page's own edge, so a page before this one exists by construction, and symmetrically for {@code
 * before}. The only way that construction could be stale is a page's entire contents disappearing
 * between requests (this app never bulk-deletes active artists -- removal is one row at a time via
 * {@code ArtistActivationService}), and even then the fallback below degrades to "the link still
 * takes you back where you came from" rather than a broken cursor -- never a duplicate or skipped
 * row on the page that DOES render, which is the guarantee issue #174 actually asks for.
 */
@Component
public class ArtistPager {

    private static final List<ArtistStatus> ACTIVE_STATUSES = List.of(ArtistStatus.SEED, ArtistStatus.APPROVED);

    private final ArtistRepository artistRepository;
    private final int pageSize;

    public ArtistPager(ArtistRepository artistRepository,
                        @Value("${setlistscout.artists-page-size:20}") int pageSize) {
        this.artistRepository = artistRepository;
        this.pageSize = pageSize;
    }

    /**
     * One page of {@code owner}'s active list. At most one of {@code after}/{@code before} should
     * be non-blank -- see the class doc for the tie-break when both are somehow present. Neither
     * present means the first page.
     */
    public ActivePage page(String owner, String after, String before, String query) {
        if (after != null && !after.isBlank()) {
            return nextPage(owner, after, query);
        }
        if (before != null && !before.isBlank()) {
            return previousPage(owner, before, query);
        }
        return firstPage(owner, query);
    }

    /**
     * #250: the filter is applied inside the keyset queries, so a filtered list keeps #174's
     * guarantee (no duplicate, no skipped row while the list mutates). Deliberately NOT
     * "fetch everything matching, then page in memory" -- that would reintroduce exactly the
     * whole-list load this page was built to avoid, and on a 3,000-row catalog it would appear
     * to work right up until it didn't.
     */
    private List<Artist> fetchFirst(String owner, String query, int limit) {
        return ArtistSearchTerm.isSearch(query)
                ? artistRepository.findActiveMatchingFirstPage(owner, ACTIVE_STATUSES,
                        ArtistSearchTerm.likePattern(query), limit)
                : artistRepository.findActiveFirstPage(owner, ACTIVE_STATUSES, limit);
    }

    private List<Artist> fetchAfter(String owner, String query, String cursor, int limit) {
        return ArtistSearchTerm.isSearch(query)
                ? artistRepository.findActiveMatchingAfter(owner, ACTIVE_STATUSES,
                        ArtistSearchTerm.likePattern(query), cursor, limit)
                : artistRepository.findActiveAfter(owner, ACTIVE_STATUSES, cursor, limit);
    }

    private List<Artist> fetchBefore(String owner, String query, String cursor, int limit) {
        return ArtistSearchTerm.isSearch(query)
                ? artistRepository.findActiveMatchingBefore(owner, ACTIVE_STATUSES,
                        ArtistSearchTerm.likePattern(query), cursor, limit)
                : artistRepository.findActiveBefore(owner, ACTIVE_STATUSES, cursor, limit);
    }

    private ActivePage firstPage(String owner, String query) {
        List<Artist> fetched = fetchFirst(owner, query, pageSize + 1);
        boolean hasNext = fetched.size() > pageSize;
        List<Artist> page = hasNext ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = hasNext ? lastCursor(page) : null;
        return new ActivePage(page, hasNext, false, nextCursor, null, query);
    }

    private ActivePage nextPage(String owner, String cursor, String query) {
        List<Artist> fetched = fetchAfter(owner, query, cursor, pageSize + 1);
        boolean hasNext = fetched.size() > pageSize;
        List<Artist> page = hasNext ? fetched.subList(0, pageSize) : fetched;
        String nextCursor = hasNext ? lastCursor(page) : null;
        // Falls back to the incoming cursor itself when the page came back empty (see class doc's
        // "stale cursor" note) -- querying findActiveBefore with the ORIGINAL cursor again
        // reproduces exactly the page this request came from, which is the correct "previous" from
        // here regardless of whether this page has any rows of its own.
        String previousCursor = page.isEmpty() ? cursor : firstCursor(page);
        return new ActivePage(page, hasNext, true, nextCursor, previousCursor, query);
    }

    private ActivePage previousPage(String owner, String cursor, String query) {
        List<Artist> fetchedDescending = fetchBefore(owner, query, cursor, pageSize + 1);
        boolean hasPrevious = fetchedDescending.size() > pageSize;
        List<Artist> trimmedDescending = hasPrevious ? fetchedDescending.subList(0, pageSize) : fetchedDescending;
        List<Artist> page = new ArrayList<>(trimmedDescending);
        Collections.reverse(page); // ascending for display -- ArtistRepository#findActiveBefore's Javadoc
        String previousCursor = hasPrevious ? firstCursor(page) : null;
        // Same stale-cursor fallback as nextPage, mirrored: an empty result hands back the original
        // cursor as the "next" anchor, so the link still returns to where the user came from.
        String nextCursor = page.isEmpty() ? cursor : lastCursor(page);
        return new ActivePage(page, true, hasPrevious, nextCursor, previousCursor, query);
    }

    private static String firstCursor(List<Artist> page) {
        return page.get(0).getNormalizedName();
    }

    private static String lastCursor(List<Artist> page) {
        return page.get(page.size() - 1).getNormalizedName();
    }
}
