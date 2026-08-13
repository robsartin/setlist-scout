package com.robsartin.setlistscout;

import com.robsartin.setlistscout.catalog.ArtistRepository;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.expansion.ExpandPoller;
import com.robsartin.setlistscout.expansion.ExpandUnitRunner;
import com.robsartin.setlistscout.scan.ScanJobRepository;
import com.robsartin.setlistscout.scan.ScanPoller;
import com.robsartin.setlistscout.scan.ScanUnitRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Phase B PR4a, Task 6: the paced claim-lease pollers must be genuinely absent by default, not
 * merely inert -- {@code @ConditionalOnProperty} means Spring never even attempts to construct
 * {@code ScanPoller}/{@code ExpandPoller} when {@code setlistscout.scan-poller-enabled} /
 * {@code expand-poller-enabled} is unset or false, so the old {@code ShowScanScheduler} batch is
 * the only thing driving scans/expansion this PR (behavior-unchanged) unless someone explicitly
 * opts in. The full real-path poller behavior (flag on, real claim/run/reschedule against
 * Postgres) is the next task -- this is just the wiring guard.
 */
class PollerConditionalWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(ScanJobRepository.class, () -> mock(ScanJobRepository.class))
            .withBean(ScanUnitRunner.class, () -> mock(ScanUnitRunner.class))
            .withBean(ExpandJobRepository.class, () -> mock(ExpandJobRepository.class))
            .withBean(ExpandUnitRunner.class, () -> mock(ExpandUnitRunner.class))
            .withBean(ArtistRepository.class, () -> mock(ArtistRepository.class))
            .withBean(PollerProperties.class, () -> new PollerProperties(
                    20, 20, Duration.ofMinutes(5).toMillis(),
                    Duration.ofDays(14), Duration.ofDays(28), 6, Map.of(), true, Duration.ofHours(2)))
            // withUserConfiguration(ScanPoller.class, ...) or @Import(ScanPoller.class, ...) both
            // try to treat the imported class as a @Configuration instance the moment its
            // condition passes, which fails ("no default constructor found") since ScanPoller is
            // a plain @Component with a real constructor to autowire. A type-filtered
            // @ComponentScan is the same registration path the real app uses to pick these classes
            // up (SpringBootApplication's implicit component scan), so it's both correct and
            // faithful to production wiring: @ConditionalOnProperty is evaluated, and a normal
            // constructor-autowired ScannedGenericBeanDefinition is created when it passes.
            .withUserConfiguration(PollerScanConfig.class);

    @Configuration
    @ComponentScan(
            basePackageClasses = {ScanPoller.class, ExpandPoller.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {ScanPoller.class, ExpandPoller.class}))
    static class PollerScanConfig {}

    @Test
    @DisplayName("both poller beans are absent by default (flags unset)")
    void pollersAbsentByDefault() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .doesNotHaveBean(ScanPoller.class)
                .doesNotHaveBean(ExpandPoller.class));
    }

    @Test
    @DisplayName("scan-poller-enabled=true creates only the scan poller bean")
    void scanPollerPresentWhenEnabled() {
        contextRunner.withPropertyValues("setlistscout.scan-poller-enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .hasSingleBean(ScanPoller.class)
                        .doesNotHaveBean(ExpandPoller.class));
    }

    @Test
    @DisplayName("expand-poller-enabled=true creates only the expand poller bean")
    void expandPollerPresentWhenEnabled() {
        contextRunner.withPropertyValues("setlistscout.expand-poller-enabled=true")
                .run(context -> assertThat(context)
                        .hasNotFailed()
                        .doesNotHaveBean(ScanPoller.class)
                        .hasSingleBean(ExpandPoller.class));
    }
}
