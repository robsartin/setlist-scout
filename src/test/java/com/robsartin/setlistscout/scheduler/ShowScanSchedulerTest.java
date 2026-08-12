package com.robsartin.setlistscout.scheduler;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ShowScanSchedulerTest {

    @Test
    void scanHasANonBlankInitialDelaySoItDoesNotRunAtStartup() throws NoSuchMethodException {
        Method scan = ShowScanScheduler.class.getMethod("scan");
        Scheduled annotation = scan.getAnnotation(Scheduled.class);

        assertThat(annotation.initialDelayString()).isNotBlank();
    }
}
