package com.robsartin.setlistscout.admin;

/**
 * One FAILED row -- scan job, expand job, or artist import -- for the admin queues page's
 * failed-work section (#201), the highest-priority part of the page: this is the only genuinely
 * invisible information today (issue #201). {@code queue} is a display label ("Scan", "Expand",
 * "Import"); {@code subject} is "what it was working on" -- an artist name plus source for a
 * scan/expand job, or the raw queued name for an import row (see {@code AdminQueueService}).
 */
public record FailedWorkRow(String queue, String owner, String subject, int attempts, String lastError) {
}
