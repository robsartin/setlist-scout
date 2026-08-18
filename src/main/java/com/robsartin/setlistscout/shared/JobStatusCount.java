package com.robsartin.setlistscout.shared;

/**
 * Projection for {@link JobRepository#countGroupedByStatus()} (#201): one row per {@link
 * JobStatus}, aggregate-counted by the database -- never a {@code findAll().size()} tally in
 * Java. Getter names must match the query's {@code AS status} / {@code AS count} aliases for
 * Spring Data's interface-projection binding to work.
 */
public interface JobStatusCount {
    JobStatus getStatus();
    long getCount();
}
