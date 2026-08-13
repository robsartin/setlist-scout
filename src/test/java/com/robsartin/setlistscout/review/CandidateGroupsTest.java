package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.CandidateGroupCount;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Pure unit tests for the Candidates page's view-model assembler: no Spring, no DB. */
class CandidateGroupsTest {

    private record Row(String via, ArtistSource source, long count) implements CandidateGroupCount {
        @Override public String getVia() { return via; }
        @Override public ArtistSource getSource() { return source; }
        @Override public long getCount() { return count; }
    }

    @Test
    void ordersBaseArtistGroupsByTotalDescending() {
        List<CandidateGroupCount> counts = List.of(
                new Row("Wilco", ArtistSource.MEMBER_EXPANSION, 1),
                new Row("Tom Petty and the Heartbreakers", ArtistSource.MEMBER_EXPANSION, 5),
                new Row("Tom Petty and the Heartbreakers", ArtistSource.SIMILAR_EXPANSION, 3));

        List<CandidateGroups.BaseArtistGroup> groups = CandidateGroups.from(counts);

        assertThat(groups).hasSize(2);
        assertThat(groups.get(0).via()).isEqualTo("Tom Petty and the Heartbreakers");
        assertThat(groups.get(0).total()).isEqualTo(8);
        assertThat(groups.get(1).via()).isEqualTo("Wilco");
        assertThat(groups.get(1).total()).isEqualTo(1);
    }

    @Test
    void ordersRelationSubgroupsAsMembersSimilarTributes() {
        List<CandidateGroupCount> counts = List.of(
                new Row("Wilco", ArtistSource.TRIBUTE_EXPANSION, 1),
                new Row("Wilco", ArtistSource.SIMILAR_EXPANSION, 2),
                new Row("Wilco", ArtistSource.MEMBER_EXPANSION, 3));

        List<CandidateGroups.RelationGroup> relationGroups = CandidateGroups.from(counts).get(0).relationGroups();

        assertThat(relationGroups).extracting(CandidateGroups.RelationGroup::source)
                .containsExactly(ArtistSource.MEMBER_EXPANSION, ArtistSource.SIMILAR_EXPANSION, ArtistSource.TRIBUTE_EXPANSION);
        assertThat(relationGroups).extracting(CandidateGroups.RelationGroup::label)
                .containsExactly("Members", "Similar", "Tributes");
        assertThat(relationGroups).extracting(CandidateGroups.RelationGroup::chipClass)
                .containsExactly("member", "similar", "tribute");
    }

    @Test
    void nullDiscoveredViaIsGroupedUnderUngrouped() {
        List<CandidateGroupCount> counts = List.of(new Row(null, ArtistSource.MEMBER_EXPANSION, 2));

        List<CandidateGroups.BaseArtistGroup> groups = CandidateGroups.from(counts);

        assertThat(groups).hasSize(1);
        assertThat(groups.get(0).via()).isEqualTo("Ungrouped");
        assertThat(groups.get(0).total()).isEqualTo(2);
    }

    @Test
    void emptyCountsProduceNoGroups() {
        assertThat(CandidateGroups.from(List.of())).isEmpty();
    }
}
