package com.robsartin.setlistscout.admin;

import java.time.Instant;

/**
 * One durable-job queue's snapshot (#201): counts by {@code shared.JobStatus}, how many rows are
 * due right now, and the single oldest {@code next_due_at} in the queue -- "is it stuck or just
 * busy?" and "how far behind is it?" in one glance. Every field comes from an aggregate query
 * (COUNT/MIN), never a loaded entity list -- see {@code admin.AdminQueueService}.
 * <p>
 * {@code oldestNextDueAt} is {@code null} only when the queue is empty (no rows at all).
 */
public record QueueCounts(long scheduled, long running, long failed, long dueNow, Instant oldestNextDueAt) {
}
