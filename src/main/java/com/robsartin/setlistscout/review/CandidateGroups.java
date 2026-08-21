package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.CandidateGroupCount;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Pure view-model assembler for the Candidates page: turns the flat (discoveredVia, source, count)
 * rows from {@code ArtistRepository.countByStatusGroupedByViaAndSource} into base-artist groups
 * (via + total pending), each holding its relation subgroups (Members/Similar/Tributes) in a stable
 * display order. No I/O, no Spring -- unit-testable in isolation.
 */
public final class CandidateGroups {

    /**
     * Bucket for a null {@code discoveredVia}. Not reachable through expansion-discovery ({@code
     * RelationDiscoveredListener} always supplies a from-artist name), but IS reachable today
     * (issue #156): a SEED artist's {@code discoveredVia} is null by construction, and {@code
     * ReviewController}'s {@code remove}/{@code reject}/{@code unreject} endpoints have no
     * status/source guard -- {@code changeStatus} accepts any transition for any of the owner's
     * artist ids -- so a SEED artist can land back in PENDING_REVIEW with that null {@code
     * discoveredVia} intact (e.g. {@code POST /artists/{id}/unreject} on a SEED artist's id in one
     * step). {@code ReviewController} translates this sentinel back to {@code discoveredVia IS
     * NULL} for its row queries, since {@code discoveredVia = 'Ungrouped'} can never match a NULL
     * column in SQL.
     */
    static final String UNGROUPED = "Ungrouped";

    private static final List<ArtistSource> RELATION_ORDER =
            List.of(ArtistSource.MEMBER_EXPANSION, ArtistSource.SIMILAR_EXPANSION, ArtistSource.TRIBUTE_EXPANSION);

    private CandidateGroups() {
    }

    public static List<BaseArtistGroup> from(List<CandidateGroupCount> counts) {
        Map<String, List<CandidateGroupCount>> byVia = counts.stream()
                .collect(Collectors.groupingBy(
                        c -> c.getVia() != null ? c.getVia() : UNGROUPED,
                        LinkedHashMap::new,
                        Collectors.toList()));

        List<BaseArtistGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<CandidateGroupCount>> entry : byVia.entrySet()) {
            List<RelationGroup> relationGroups = entry.getValue().stream()
                    .filter(c -> c.getSource() != null)
                    .sorted(Comparator.comparingInt(c -> relationOrderIndex(c.getSource())))
                    .map(c -> new RelationGroup(c.getSource(), label(c.getSource()), chipClass(c.getSource()), c.getCount()))
                    .toList();

            long total = relationGroups.stream().mapToLong(RelationGroup::count).sum();
            groups.add(new BaseArtistGroup(entry.getKey(), total, relationGroups));
        }

        // Stable sort: ties keep their first-seen order (insertion order of the LinkedHashMap above).
        groups.sort(Comparator.comparingLong(BaseArtistGroup::total).reversed());
        return groups;
    }

    /**
     * Picks the "current" group to focus review on: {@code requestedVia} if it's still present in
     * {@code groups} (has pending rows), otherwise the biggest group ({@code groups} is already
     * sorted total-descending by {@link #from}). A {@code null} or stale/cleared {@code
     * requestedVia} falling through to that same biggest-first pick is the whole auto-advance
     * mechanism (issue #148) -- callers don't need a separate "is this group still there" check,
     * just re-resolve against a freshly re-queried {@code groups} after any status change.
     */
    public static ResolvedGroups resolve(List<BaseArtistGroup> groups, String requestedVia) {
        if (groups.isEmpty()) {
            return new ResolvedGroups(null, List.of());
        }
        BaseArtistGroup current = groups.stream()
                .filter(g -> g.via().equals(requestedVia))
                .findFirst()
                .orElse(groups.get(0));
        List<BaseArtistGroup> others = groups.stream().filter(g -> g != current).toList();
        return new ResolvedGroups(current, others);
    }

    private static int relationOrderIndex(ArtistSource source) {
        int idx = RELATION_ORDER.indexOf(source);
        return idx == -1 ? RELATION_ORDER.size() : idx;
    }

    /** Display name for a relation type -- also used for bulk-action announcements (issue #155). */
    static String label(ArtistSource source) {
        return switch (source) {
            case MEMBER_EXPANSION -> "Members";
            case SIMILAR_EXPANSION -> "Similar";
            case TRIBUTE_EXPANSION -> "Tributes";
            case SEED_LIST -> "Seed";
            // #206 Task 4: VENUE_EXPANSION candidates carry no discoveredVia (the venue that
            // surfaced them isn't part of the event), so they always land in the UNGROUPED bucket
            // rather than under a base-artist group -- added here only to keep this switch
            // exhaustive; Task 5 owns any further review-page treatment.
            case VENUE_EXPANSION -> "Venue";
        };
    }

    private static String chipClass(ArtistSource source) {
        return switch (source) {
            case MEMBER_EXPANSION -> "member";
            case SIMILAR_EXPANSION -> "similar";
            case TRIBUTE_EXPANSION -> "tribute";
            case SEED_LIST -> "seed";
            case VENUE_EXPANSION -> "venue";
        };
    }

    /** One base artist's pending candidates, grouped by relation type. */
    public record BaseArtistGroup(String via, long total, List<RelationGroup> relationGroups) {
    }

    /** One relation-type subgroup within a base-artist group. */
    public record RelationGroup(ArtistSource source, String label, String chipClass, long count) {
    }

    /** {@code current} is null only when there are no pending groups at all. */
    public record ResolvedGroups(BaseArtistGroup current, List<BaseArtistGroup> others) {
    }
}
