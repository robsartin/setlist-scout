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

    /** Bucket for a null discoveredVia. Defensive: no current expansion path produces one. */
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

    private static int relationOrderIndex(ArtistSource source) {
        int idx = RELATION_ORDER.indexOf(source);
        return idx == -1 ? RELATION_ORDER.size() : idx;
    }

    private static String label(ArtistSource source) {
        return switch (source) {
            case MEMBER_EXPANSION -> "Members";
            case SIMILAR_EXPANSION -> "Similar";
            case TRIBUTE_EXPANSION -> "Tributes";
            case SEED_LIST -> "Seed";
        };
    }

    private static String chipClass(ArtistSource source) {
        return switch (source) {
            case MEMBER_EXPANSION -> "member";
            case SIMILAR_EXPANSION -> "similar";
            case TRIBUTE_EXPANSION -> "tribute";
            case SEED_LIST -> "seed";
        };
    }

    /** One base artist's pending candidates, grouped by relation type. */
    public record BaseArtistGroup(String via, long total, List<RelationGroup> relationGroups) {
    }

    /** One relation-type subgroup within a base-artist group. */
    public record RelationGroup(ArtistSource source, String label, String chipClass, long count) {
    }
}
