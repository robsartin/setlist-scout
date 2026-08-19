package com.robsartin.setlistscout.catalog;

/**
 * Projection for {@link ArtistImportRepository#countGroupedByOwnerAndStatus()} (#201): one row
 * per (owner, status), aggregate-counted by the database. Getter names must match the query's
 * {@code AS owner} / {@code AS status} / {@code AS count} aliases for Spring Data's
 * interface-projection binding to work.
 */
public interface ImportOwnerStatusCount {
    String getOwner();
    ArtistImportStatus getStatus();
    long getCount();
}
