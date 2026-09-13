package com.robsartin.setlistscout.catalog;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #279: the one place that turns (source, discoveredVia) into something a person can read.
 *
 * <p>Before this, an active row said {@code MEMBER_EXPANSION} and stopped. For 1,162 rows that is
 * the mechanism and not the answer -- whose member is the whole of what makes the name worth
 * keeping. The parent was already stored and already rendered on the rejected list; only the active
 * list went without.
 */
class ArtistProvenanceTest {

    @Test
    @DisplayName("a hand-entered artist says so, and names no parent -- it was not discovered via anything")
    void seedListNamesNoParent() {
        assertThat(ArtistProvenance.describe(ArtistSource.SEED_LIST, null)).isEqualTo("Seed list");
    }

    @Test
    @DisplayName("issue #279: a band member names the act -- 'whose member' is the whole answer")
    void memberNamesTheAct() {
        assertThat(ArtistProvenance.describe(ArtistSource.MEMBER_EXPANSION, "Tom Petty"))
                .isEqualTo("Member of Tom Petty");
    }

    @Test
    @DisplayName("issue #279: a similarity match names what it was similar to")
    void similarNamesTheSeed() {
        assertThat(ArtistProvenance.describe(ArtistSource.SIMILAR_EXPANSION, "Jackson Browne"))
                .isEqualTo("Similar to Jackson Browne");
    }

    @Test
    @DisplayName("issue #279: a tribute act names who it pays tribute to")
    void tributeNamesTheSubject() {
        assertThat(ArtistProvenance.describe(ArtistSource.TRIBUTE_EXPANSION, "Led Zeppelin"))
                .isEqualTo("Tribute to Led Zeppelin");
    }

    /**
     * {@code VenuePerformerListener} inserts these with a null {@code discoveredVia} -- the venue is
     * not recorded on the artist row. The phrase must therefore not promise a parent it cannot name.
     */
    @Test
    @DisplayName("issue #279: a venue performer names no parent, because none is stored")
    void venueNamesNoParent() {
        assertThat(ArtistProvenance.describe(ArtistSource.VENUE_EXPANSION, null))
                .isEqualTo("Seen at a followed venue");
    }

    /**
     * Defensive: every expansion-derived active artist in production has a {@code discoveredVia}
     * (measured 2026-09-13: 2,582 of 2,582). If one ever did not, the phrase must degrade to the
     * mechanism rather than render "Member of null" or a dangling "Member of".
     */
    @Test
    @DisplayName("issue #279: an expansion with no stored parent degrades to the mechanism, never "
            + "to 'Member of null' or a dangling preposition")
    void expansionWithoutAParentDegradesCleanly() {
        assertThat(ArtistProvenance.describe(ArtistSource.MEMBER_EXPANSION, null)).isEqualTo("Band member");
        assertThat(ArtistProvenance.describe(ArtistSource.SIMILAR_EXPANSION, null)).isEqualTo("Similar artist");
        assertThat(ArtistProvenance.describe(ArtistSource.TRIBUTE_EXPANSION, null)).isEqualTo("Tribute act");
        assertThat(ArtistProvenance.describe(ArtistSource.MEMBER_EXPANSION, "   ")).isEqualTo("Band member");
    }

    @Test
    @DisplayName("issue #279: every ArtistSource has a phrase -- a new one cannot render as a raw enum by default")
    void everySourceHasAPhrase() {
        for (ArtistSource source : ArtistSource.values()) {
            assertThat(ArtistProvenance.describe(source, "Someone"))
                    .as("%s must have a readable phrase", source)
                    .isNotBlank()
                    .doesNotContain("_")
                    .isNotEqualTo(source.name());
        }
    }
}
