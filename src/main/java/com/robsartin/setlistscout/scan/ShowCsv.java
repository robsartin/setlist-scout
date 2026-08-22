package com.robsartin.setlistscout.scan;

import java.util.List;

/**
 * The shows-CSV header and per-row mapping (issue #228), shared by {@code
 * ShowController#showsCsv} ({@code /shows.csv}) and {@code SharedScanController}'s per-pairing
 * {@code /shared/{id}.csv} -- both export the identical nine columns for a {@link Show}, so this
 * is the one place that mapping is written, not two copies that could drift apart on a column.
 * <p>
 * Deliberately package-private: both callers already live in {@code scan}, and this is
 * presentation glue for {@link com.robsartin.setlistscout.shared.CsvResponses}, not a type either
 * of them needs to expose further.
 */
final class ShowCsv {

    /** Column order fixed by issue #228's brief. */
    static final List<String> HEADER = List.of(
            "artist_name", "event_date_time", "venue_name", "venue_city",
            "kind", "source", "ticket_url", "price", "hidden_at");

    private ShowCsv() {
    }

    /** One CSV row for {@code show}, in {@link #HEADER} order. Nullable fields render as "", never the string "null". */
    static List<String> row(Show show) {
        return List.of(
                orBlank(show.getArtistName()),
                show.getEventDateTime() == null ? "" : show.getEventDateTime().toString(),
                orBlank(show.getVenueName()),
                orBlank(show.getVenueCity()),
                show.getKind() == null ? "" : show.getKind().name(),
                orBlank(show.getSource()),
                orBlank(show.getTicketUrl()),
                show.getPrice() == null ? "" : show.getPrice().toPlainString(),
                show.getHiddenAt() == null ? "" : show.getHiddenAt().toString());
    }

    private static String orBlank(String s) {
        return s == null ? "" : s;
    }
}
