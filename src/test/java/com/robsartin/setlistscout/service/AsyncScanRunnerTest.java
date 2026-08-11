package com.robsartin.setlistscout.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class AsyncScanRunnerTest {

    private static final String OWNER = "rob@example.com";

    @Test
    void startScanRunsTheScanThenClearsRunningState() {
        ShowAggregationService aggregation = mock(ShowAggregationService.class);
        ScanStateService state = new ScanStateService();
        // Same-thread executor so the async work completes inline for the test.
        AsyncScanRunner runner = new AsyncScanRunner(aggregation, state, Runnable::run);

        runner.startScan(OWNER);

        verify(aggregation).scanForShows(OWNER);
        assertThat(state.isRunning(OWNER)).isFalse();   // finished -> ready for another scan
    }

    @Test
    void startScanIsIgnoredWhenAScanIsAlreadyRunning() {
        ShowAggregationService aggregation = mock(ShowAggregationService.class);
        ScanStateService state = new ScanStateService();
        AsyncScanRunner runner = new AsyncScanRunner(aggregation, state, Runnable::run);
        // Simulate a scan already in flight for this owner.
        state.tryStart(OWNER);

        runner.startScan(OWNER);

        verify(aggregation, never()).scanForShows(OWNER);
    }

    @Test
    void runningStateIsClearedEvenWhenTheScanThrows() {
        ShowAggregationService aggregation = mock(ShowAggregationService.class);
        org.mockito.Mockito.doThrow(new RuntimeException("ticketmaster down"))
                .when(aggregation).scanForShows(OWNER);
        ScanStateService state = new ScanStateService();
        AsyncScanRunner runner = new AsyncScanRunner(aggregation, state, Runnable::run);

        runner.startScan(OWNER);

        assertThat(state.isRunning(OWNER)).isFalse();
    }
}
