package com.robsartin.setlistscout.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #228: the shows-CSV header and per-row mapping shared by {@code ShowController#showsCsv}
 * and {@code SharedScanController}'s per-pairing CSV -- both export the identical nine columns for
 * a {@link Show}, so this is the one place that mapping is written.
 */
class ShowCsvTest {

    @Test
    @DisplayName("HEADER names the nine columns issue #228 specifies, in order")
    void headerMatchesIssueColumns() {
        assertThat(ShowCsv.HEADER).containsExactly(
                "artist_name", "event_date_time", "venue_name", "venue_city",
                "kind", "source", "ticket_url", "price", "hidden_at");
    }

    @Test
    @DisplayName("row() carries every field through, in header order")
    void rowCarriesEveryField() {
        Show show = new Show("Wilco", LocalDateTime.of(2026, 9, 5, 20, 0), "Moody Center", "Austin",
                new BigDecimal("42.50"), "ticketmaster", "https://tix.example/1", Show.Kind.MUSIC);
        show.setHiddenAt(Instant.parse("2026-08-01T12:00:00Z"));

        List<String> row = ShowCsv.row(show);

        assertThat(row).containsExactly("Wilco", "2026-09-05T20:00", "Moody Center", "Austin",
                "MUSIC", "ticketmaster", "https://tix.example/1", "42.50", "2026-08-01T12:00:00Z");
    }

    @Test
    @DisplayName("nullable fields (venueCity, price, ticketUrl, hiddenAt) render as blank, not the string \"null\"")
    void nullableFieldsRenderBlank() {
        Show show = new Show("Wilco", LocalDateTime.of(2026, 9, 5, 20, 0), "Moody Center", null,
                null, "ticketmaster", null, Show.Kind.MUSIC);

        List<String> row = ShowCsv.row(show);

        assertThat(row).containsExactly("Wilco", "2026-09-05T20:00", "Moody Center", "",
                "MUSIC", "ticketmaster", "", "", "");
    }
}
