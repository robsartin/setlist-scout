package com.robsartin.setlistscout.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #223: a non-hidden row now carries TWO action buttons (Hide, Hide &amp; Stop Following),
 * where issue #166 only ever had one (Hide XOR Unhide, mutually exclusive on {@code show.hidden}).
 * {@link ShowActionOutcome#focusesRow(Long, String)} therefore needs an {@code action} discriminator
 * alongside the row id -- without it, an outcome naming a row with two live buttons would make BOTH
 * satisfy {@code th:autofocus}, breaking the "exactly one autofocus per response" contract
 * {@code AbstractPostgresIntegrationTest#countAutofocusElements} enforces. Pure -- no Spring, no I/O.
 */
class ShowActionOutcomeTest {

    private static Show row(String artistName, long id) {
        Show show = new Show(artistName, LocalDateTime.now().plusDays(1), "Venue", "City",
                BigDecimal.TEN, "ticketmaster", null, Show.Kind.MUSIC);
        ReflectionTestUtils.setField(show, "id", id);
        return show;
    }

    @Test
    @DisplayName("afterRow focuses the successor with the given action")
    void afterRowPicksTheSuccessor() {
        List<Show> rows = List.of(row("Alpha", 1L), row("Bravo", 2L), row("Charlie", 3L));

        ShowActionOutcome outcome = ShowActionOutcome.afterRow(rows, 2L, "hide-and-cancel", "Hid Bravo.");

        assertThat(outcome.focus()).isEqualTo(ShowActionOutcome.Focus.ROW);
        assertThat(outcome.showId()).isEqualTo(3L);
        assertThat(outcome.action()).isEqualTo("hide-and-cancel");
        assertThat(outcome.message()).isEqualTo("Hid Bravo.");
    }

    @Test
    @DisplayName("afterRow falls back to the anchor when the acted-on row was last, empty, or unknown")
    void afterRowDegradesToAnchor() {
        assertThat(ShowActionOutcome.afterRow(List.of(row("Alpha", 1L)), 1L, "hide", null).focus())
                .isEqualTo(ShowActionOutcome.Focus.ANCHOR);
        assertThat(ShowActionOutcome.afterRow(List.of(), 1L, "hide", null).focus())
                .isEqualTo(ShowActionOutcome.Focus.ANCHOR);
        assertThat(ShowActionOutcome.afterRow(List.of(row("Alpha", 1L), row("Bravo", 2L)), 99L, "hide", null).focus())
                .isEqualTo(ShowActionOutcome.Focus.ANCHOR);
    }

    @Test
    @DisplayName("issue #223: focusesRow matches only its own id AND its own action -- a row with two "
            + "live buttons (Hide, Hide & Stop Following) never satisfies both at once")
    void focusesRowIsExactOnBothIdAndAction() {
        ShowActionOutcome outcome = ShowActionOutcome.afterRow(
                List.of(row("Alpha", 1L), row("Bravo", 2L)), 1L, "hide", null);

        assertThat(outcome.focusesRow(2L, "hide")).isTrue();
        assertThat(outcome.focusesRow(2L, "hide-and-cancel"))
                .as("the OTHER button on the same row -- must not also claim autofocus").isFalse();
        assertThat(outcome.focusesRow(2L, "unhide")).isFalse();
        assertThat(outcome.focusesRow(1L, "hide")).as("wrong row").isFalse();
        assertThat(outcome.focusesAnchor()).isFalse();
    }

    @Test
    @DisplayName("row() and anchor() carry the action/no-row contract directly")
    void rowAndAnchorFactories() {
        ShowActionOutcome rowOutcome = ShowActionOutcome.row(5L, "unhide", "Unhid Radiohead.");
        assertThat(rowOutcome.focus()).isEqualTo(ShowActionOutcome.Focus.ROW);
        assertThat(rowOutcome.focusesRow(5L, "unhide")).isTrue();
        assertThat(rowOutcome.focusesRow(5L, "hide")).isFalse();

        ShowActionOutcome anchor = ShowActionOutcome.anchor("Hid Radiohead at Moody Center.");
        assertThat(anchor.focusesAnchor()).isTrue();
        assertThat(anchor.showId()).isNull();
        assertThat(anchor.focusesRow(5L, "hide")).isFalse();
    }

    @Test
    @DisplayName("downgradedToAnchor drops the row/action target, keeps the message, no-ops on ANCHOR")
    void downgradedToAnchorDropsTheSpecificTarget() {
        ShowActionOutcome rowOutcome = ShowActionOutcome.row(5L, "hide-and-cancel", "Hid Radiohead.");

        ShowActionOutcome downgraded = rowOutcome.downgradedToAnchor();

        assertThat(downgraded.focusesAnchor()).isTrue();
        assertThat(downgraded.showId()).isNull();
        assertThat(downgraded.message()).isEqualTo("Hid Radiohead.");

        ShowActionOutcome anchor = ShowActionOutcome.anchor("Hid Radiohead.");
        assertThat(anchor.downgradedToAnchor()).isSameAs(anchor);
    }
}
