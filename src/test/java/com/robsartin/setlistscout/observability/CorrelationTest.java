package com.robsartin.setlistscout.observability;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CorrelationTest {

    @Test
    void runStampsFieldsDuringBodyAndClearsAfter() {
        AtomicReference<String> cidDuring = new AtomicReference<>();
        AtomicReference<String> jobDuring = new AtomicReference<>();
        AtomicReference<String> ownerDuring = new AtomicReference<>();
        AtomicReference<String> parentDuring = new AtomicReference<>();

        Correlation.run("scan", "rob@example.com", "parent-123", () -> {
            cidDuring.set(MDC.get(Correlation.CID));
            jobDuring.set(MDC.get(Correlation.JOB));
            ownerDuring.set(MDC.get(Correlation.OWNER));
            parentDuring.set(MDC.get(Correlation.PARENT_CID));
        });

        assertThat(CorrelationIds.isValid(cidDuring.get())).isTrue();
        assertThat(jobDuring.get()).isEqualTo("scan");
        assertThat(ownerDuring.get()).isEqualTo("rob@example.com");
        assertThat(parentDuring.get()).isEqualTo("parent-123");

        assertThat(MDC.get(Correlation.CID)).isNull();
        assertThat(MDC.get(Correlation.JOB)).isNull();
        assertThat(MDC.get(Correlation.OWNER)).isNull();
        assertThat(MDC.get(Correlation.PARENT_CID)).isNull();
    }

    @Test
    void runClearsMdcEvenWhenBodyThrows() {
        assertThatThrownBy(() -> Correlation.run("scan", "rob@example.com", null,
                () -> { throw new IllegalStateException("boom"); }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(MDC.get(Correlation.CID)).isNull();
        assertThat(MDC.get(Correlation.JOB)).isNull();
        assertThat(MDC.get(Correlation.OWNER)).isNull();
    }

    @Test
    void nullOwnerAndParentAreOmitted() {
        AtomicReference<String> ownerDuring = new AtomicReference<>("sentinel");
        Correlation.run("expansion", null, null,
                () -> ownerDuring.set(MDC.get(Correlation.OWNER)));
        assertThat(ownerDuring.get()).isNull();
    }
}
