package com.robsartin.setlistscout.shared;

public enum JobStatus {
    SCHEDULED, // due at next_due_at, not currently claimed by a worker
    RUNNING,   // claimed by a worker and currently executing
    FAILED     // most recent run threw; last_error holds the detail
}
