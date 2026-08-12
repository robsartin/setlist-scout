package com.robsartin.setlistscout.catalog;

public enum ArtistStatus {
    SEED,            // from the original hand-curated list -- always active
    PENDING_REVIEW,  // discovered via member/similar expansion, awaiting approval
    APPROVED,        // reviewed and approved -- included in show search
    REJECTED         // reviewed and rejected -- excluded from show search
}
