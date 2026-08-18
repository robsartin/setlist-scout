package com.robsartin.setlistscout.admin;

/**
 * One owner's {@code artist_import} queue state (#201): pending / done / failed counts, from
 * {@code catalog.ArtistImportRepository#countGroupedByOwnerAndStatus}'s aggregate query.
 */
public record ImportOwnerCounts(String owner, long pending, long done, long failed) {
}
