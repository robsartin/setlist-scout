package com.robsartin.setlistscout.shared.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link Correlation#runWithNewId} is the background-job counterpart to
 * {@link CorrelationIdFilter}'s per-HTTP-request MDC scoping -- see {@code CorrelationIdFilterTest}
 * for the request-path version of these same three properties (a valid id is visible during the
 * scoped work, it's cleared after, and it's cleared even when the work throws).
 */
class CorrelationTest {

    @Test
    void putsAValidCidInMdcDuringTheJobAndClearsItAfter() {
        AtomicReference<String> cidDuring = new AtomicReference<>();

        Correlation.runWithNewId(() -> cidDuring.set(MDC.get(Correlation.CID)));

        assertThat(CorrelationIds.isValid(cidDuring.get())).isTrue();
        assertThat(MDC.get(Correlation.CID)).as("cleared after the job completes").isNull();
    }

    @Test
    void clearsMdcEvenWhenTheJobThrows() {
        assertThatThrownBy(() -> Correlation.runWithNewId(() -> {
            throw new RuntimeException("boom");
        })).isInstanceOf(RuntimeException.class).hasMessage("boom");

        assertThat(MDC.get(Correlation.CID)).as("cleared even on the exception path").isNull();
    }

    @Test
    void consecutiveRunsGetDifferentIds() {
        AtomicReference<String> first = new AtomicReference<>();
        AtomicReference<String> second = new AtomicReference<>();

        Correlation.runWithNewId(() -> first.set(MDC.get(Correlation.CID)));
        Correlation.runWithNewId(() -> second.set(MDC.get(Correlation.CID)));

        assertThat(first.get()).isNotBlank();
        assertThat(second.get()).isNotBlank();
        assertThat(first.get()).as("a fresh id per run, not the same one reused").isNotEqualTo(second.get());
    }
}
