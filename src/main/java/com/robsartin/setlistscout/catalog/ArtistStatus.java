package com.robsartin.setlistscout.catalog;

public enum ArtistStatus {
    SEED,            // from the original hand-curated list -- always active
    PENDING_REVIEW,  // discovered via member/similar expansion, awaiting approval
    APPROVED,        // reviewed and approved -- included in show search
    REJECTED,        // reviewed and rejected -- excluded from show search

    /**
     * A hand-curated {@code SEED} artist the owner no longer wants tracked. Distinct from {@link
     * #REJECTED}: REJECTED is the outcome of reviewing an expansion candidate and drives the
     * Rejected review-queue page (with its own unreject flow); REMOVED is the owner taking a seed
     * off their list, which is not a "rejected candidate" and must not clutter that queue.
     * <p>
     * Intentionally excluded from {@link ArtistActivationService#isActive}, which already returns
     * true only for {@code SEED}/{@code APPROVED} -- no code change was needed there, just this
     * enum value (pinned by {@code ArtistActivationServiceTest#isActiveClassifiesEachStatus}).
     */
    REMOVED
}
