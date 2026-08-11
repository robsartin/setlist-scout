package com.robsartin.setlistscout.service;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Runs {@link ShowAggregationService#scanForShows} off the request thread so "Scan now" returns
 * immediately (a full scan hits Ticketmaster + Bandsintown for every active artist and can take a
 * while). {@link ScanStateService} guards against overlapping scans and drives the polling UI.
 *
 * <p>A single-thread executor is enough: scans are infrequent and one-at-a-time per owner, and
 * serialising them keeps outbound-API load modest for this small personal app.
 */
@Service
public class AsyncScanRunner {

    private static final Logger log = LoggerFactory.getLogger(AsyncScanRunner.class);

    private final ShowAggregationService aggregation;
    private final ScanStateService scanState;
    private final Executor executor;

    @Autowired
    public AsyncScanRunner(ShowAggregationService aggregation, ScanStateService scanState) {
        this(aggregation, scanState, Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "scan-runner");
            thread.setDaemon(true);
            return thread;
        }));
    }

    // Package-private: lets tests inject a same-thread executor.
    AsyncScanRunner(ShowAggregationService aggregation, ScanStateService scanState, Executor executor) {
        this.aggregation = aggregation;
        this.scanState = scanState;
        this.executor = executor;
    }

    /**
     * Start a scan for this owner on the background executor, unless one is already running (in
     * which case this is a no-op -- the overlap guard). Returns immediately.
     */
    public void startScan(String owner) {
        if (!scanState.tryStart(owner)) {
            return;
        }
        executor.execute(() -> {
            try {
                aggregation.scanForShows(owner);
            } catch (RuntimeException e) {
                log.error("Show scan failed for {}", owner, e);
            } finally {
                scanState.finish(owner);
            }
        });
    }

    @PreDestroy
    void shutdown() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
        }
    }
}
