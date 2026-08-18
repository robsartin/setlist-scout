package com.robsartin.setlistscout.catalog;

/**
 * Lifecycle of one queued import name (#177).
 * <p>
 * Deliberately not {@code shared.JobStatus}: a scan or expand job is recurring and returns to
 * SCHEDULED after every run, whereas an import row is terminal -- once the name is seeded it is
 * DONE and never runs again. Sharing the enum would blur that difference.
 */
public enum ArtistImportStatus {
    /** Queued, not yet seeded. The only status the partial unique index constrains. */
    PENDING,
    /** Seeded successfully. Terminal. */
    DONE,
    /** Gave up after the retry cap. Terminal, and kept so the owner can see what failed and why. */
    FAILED
}
