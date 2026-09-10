package com.robsartin.setlistscout.admin;

import com.robsartin.setlistscout.scan.SourceStatusRow;

import java.util.List;

/**
 * The whole admin queues page's model, assembled by {@link AdminQueueService#snapshot()} (#201):
 * scan/expand queue counts, import-queue state per owner, every FAILED row across all three
 * queues, and (#273) every show source's health -- healthy ones included, since the Shows-page
 * banner from #265 only ever appears once a source is already dead. A plain record -- no Spring, no I/O -- so the controller can hand it straight to the
 * view.
 */
public record AdminQueueSnapshot(
        QueueCounts scanCounts,
        QueueCounts expandCounts,
        List<ImportOwnerCounts> importCounts,
        List<FailedWorkRow> failedWork,
        List<SourceStatusRow> sourceHealth
) {
}
