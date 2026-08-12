package com.robsartin.setlistscout.shared.observability;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.logging.LogLevel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class LogLevelControllerTest {

    private LoggingSystem loggingSystem;
    private LogLevelController controller;

    @BeforeEach
    void setUp() {
        loggingSystem = mock(LoggingSystem.class);
        controller = new LogLevelController(loggingSystem);
    }

    @Test
    void setsTheLevelForOurPackageAndRedirects() {
        String view = controller.setLevel("DEBUG");

        assertThat(view).isEqualTo("redirect:/artists");
        verify(loggingSystem).setLogLevel("com.robsartin.setlistscout", LogLevel.DEBUG);
    }

    @Test
    void lowercaseLevelIsAccepted() {
        controller.setLevel("info");
        verify(loggingSystem).setLogLevel("com.robsartin.setlistscout", LogLevel.INFO);
    }

    @Test
    void invalidLevelIsIgnoredWithoutThrowing() {
        String view = controller.setLevel("bogus");

        assertThat(view).isEqualTo("redirect:/artists");
        verify(loggingSystem, never()).setLogLevel(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }
}
