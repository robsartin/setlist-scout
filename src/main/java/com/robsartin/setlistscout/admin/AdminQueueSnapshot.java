package com.robsartin.setlistscout.admin;

import java.util.List;

/**
 * The whole admin queues page's model, assembled by {@link AdminQueueService#snapshot()} (#201):
 * scan/expand queue counts, import-queue state per owner, and every FAILED row across all three
 * queues. A plain record -- no Spring, no I/O -- so the controller can hand it straight to the
 * view.
 */
public record AdminQueueSnapshot(
        QueueCounts scanCounts,
        QueueCounts expandCounts,
        List<ImportOwnerCounts> importCounts,
        List<FailedWorkRow> failedWork
) {
}
