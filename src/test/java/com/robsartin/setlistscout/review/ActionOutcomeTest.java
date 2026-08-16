package com.robsartin.setlistscout.review;

import com.robsartin.setlistscout.catalog.Artist;
import com.robsartin.setlistscout.catalog.ArtistSource;
import com.robsartin.setlistscout.catalog.ArtistStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ActionOutcomeTest {

    private static Artist row(String name, long id) {
        Artist artist = new Artist(name, ArtistSource.MEMBER_EXPANSION, ArtistStatus.PENDING_REVIEW,
                "Tom Petty and the Heartbreakers", "note");
        ReflectionTestUtils.setField(artist, "id", id);
        return artist;
    }

    @Test
    @DisplayName("afterRow focuses the row that follows the acted-on one")
    void afterRowPicksTheSuccessor() {
        List<Artist> rows = List.of(row("Alpha", 1L), row("Bravo", 2L), row("Charlie", 3L));

        ActionOutcome outcome = ActionOutcome.afterRow(rows, 2L, "approve", "Approved Bravo.");

        assertThat(outcome.focus()).isEqualTo(ActionOutcome.Focus.ROW);
        assertThat(outcome.artistId()).isEqualTo(3L);
        assertThat(outcome.decision()).isEqualTo("approve");
        assertThat(outcome.message()).isEqualTo("Approved Bravo.");
    }

    @Test
    @DisplayName("afterRow falls back to the group anchor when the acted-on row was last")
    void afterRowOnLastRowFallsBackToAnchor() {
        List<Artist> rows = List.of(row("Alpha", 1L), row("Bravo", 2L));

        ActionOutcome outcome = ActionOutcome.afterRow(rows, 2L, "reject", "Rejected Bravo.");

        assertThat(outcome.focus()).isEqualTo(ActionOutcome.Focus.ANCHOR);
        assertThat(outcome.artistId()).isNull();
        assertThat(outcome.message()).isEqualTo("Rejected Bravo.");
    }

    @Test
    @DisplayName("afterRow falls back to the anchor for a single-row list, an empty list, and an unknown id")
    void afterRowDegradesToAnchor() {
        assertThat(ActionOutcome.afterRow(List.of(row("Alpha", 1L)), 1L, "approve", null).focus())
                .isEqualTo(ActionOutcome.Focus.ANCHOR);
        assertThat(ActionOutcome.afterRow(List.of(), 1L, "approve", null).focus())
                .isEqualTo(ActionOutcome.Focus.ANCHOR);
        assertThat(ActionOutcome.afterRow(List.of(row("Alpha", 1L), row("Bravo", 2L)), 99L, "approve", null).focus())
                .isEqualTo(ActionOutcome.Focus.ANCHOR);
    }

    @Test
    @DisplayName("focusesRow matches only its own id AND its own decision")
    void focusesRowIsExact() {
        ActionOutcome outcome = ActionOutcome.afterRow(
                List.of(row("Alpha", 1L), row("Bravo", 2L)), 1L, "approve", null);

        assertThat(outcome.focusesRow(2L, "approve")).isTrue();
        assertThat(outcome.focusesRow(2L, "reject")).as("the other button in the same row").isFalse();
        assertThat(outcome.focusesRow(1L, "approve")).isFalse();
        assertThat(outcome.focusesAnchor()).isFalse();
    }

    @Test
    @DisplayName("anchor and keepFocus carry no row, and keepFocus focuses nothing at all")
    void anchorAndKeepFocus() {
        assertThat(ActionOutcome.anchor("Approved all 12 Members from Wilco.").focusesAnchor()).isTrue();
        assertThat(ActionOutcome.anchor("m").focusesRow(1L, "approve")).isFalse();

        ActionOutcome kept = ActionOutcome.keepFocus("Expansion requested.");
        assertThat(kept.focus()).isEqualTo(ActionOutcome.Focus.NONE);
        assertThat(kept.focusesAnchor()).isFalse();
        assertThat(kept.focusesRow(1L, "approve")).isFalse();
        assertThat(kept.message()).isEqualTo("Expansion requested.");
    }

    @Test
    @DisplayName("withoutRowFocus downgrades ROW to the anchor and leaves the message and other kinds alone")
    void withoutRowFocusDowngrades() {
        ActionOutcome row = ActionOutcome.afterRow(
                List.of(row("Alpha", 1L), row("Bravo", 2L)), 1L, "approve", "Approved Alpha.");

        ActionOutcome downgraded = row.withoutRowFocus();

        assertThat(downgraded.focusesAnchor()).isTrue();
        assertThat(downgraded.artistId()).isNull();
        assertThat(downgraded.message()).isEqualTo("Approved Alpha.");

        ActionOutcome kept = ActionOutcome.keepFocus("Expansion requested.");
        assertThat(kept.withoutRowFocus()).isEqualTo(kept);
    }
}
