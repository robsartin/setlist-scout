package com.robsartin.setlistscout.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScanStateServiceTest {

    private static final String ALICE = "alice@example.com";
    private static final String BOB = "bob@example.com";

    @Test
    void tryStartSucceedsOnceThenGuardsAgainstOverlap() {
        ScanStateService state = new ScanStateService();

        assertThat(state.tryStart(ALICE)).isTrue();
        assertThat(state.isRunning(ALICE)).isTrue();
        // A second "Scan now" while one is running must be ignored.
        assertThat(state.tryStart(ALICE)).isFalse();
    }

    @Test
    void finishClearsStateAndAllowsANewScan() {
        ScanStateService state = new ScanStateService();
        state.tryStart(ALICE);

        state.finish(ALICE);

        assertThat(state.isRunning(ALICE)).isFalse();
        assertThat(state.tryStart(ALICE)).isTrue();
    }

    @Test
    void stateIsTrackedPerOwner() {
        ScanStateService state = new ScanStateService();
        state.tryStart(ALICE);

        assertThat(state.isRunning(BOB)).isFalse();
        // Bob can start his own scan even while Alice's is running.
        assertThat(state.tryStart(BOB)).isTrue();
    }
}
