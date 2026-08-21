package com.robsartin.setlistscout.scan;

import java.time.Instant;

/**
 * Display row for one followed venue on the {@code /venues} management page (#206 Task 6):
 * the venue itself, when its scan job last ran (null if it has never run yet), and how many
 * shows it has contributed to date. {@code lastScanned}/{@code showCount} are load-bearing, not
 * decoration -- a venue whose calendar silently stops parsing looks identical to a venue with no
 * shows unless the page can tell "never scanned" apart from "scanned and found nothing" (see
 * {@code VenueController} and this task's report for the #211 precedent this exists to avoid).
 * Mirrors {@code catalog.ArtistEdgeView}'s own record-as-view-model shape.
 */
public record VenueRow(Venue venue, Instant lastScanned, long showCount) {
}
