package com.robsartin.setlistscout;

import com.robsartin.setlistscout.expansion.DiscogsService;
import com.robsartin.setlistscout.expansion.LastFmService;
import com.robsartin.setlistscout.expansion.SimilarArtistLlmService;
import com.robsartin.setlistscout.expansion.TributeLlmService;
import com.robsartin.setlistscout.expansion.source.DiscogsRelationSource;
import com.robsartin.setlistscout.expansion.source.LastFmSimilarSource;
import com.robsartin.setlistscout.expansion.source.MusicBrainzRelationSource;
import com.robsartin.setlistscout.expansion.source.SimilarLlmSource;
import com.robsartin.setlistscout.expansion.source.TributeLlmSource;
import com.robsartin.setlistscout.scan.BandSiteScraperService;
import com.robsartin.setlistscout.scan.BandsintownService;
import com.robsartin.setlistscout.scan.TicketmasterService;
import com.robsartin.setlistscout.scan.source.BandSiteShowSource;
import com.robsartin.setlistscout.scan.source.BandsintownShowSource;
import com.robsartin.setlistscout.scan.source.TicketmasterShowSource;
import com.robsartin.setlistscout.settings.GeocodingService;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Issue #139: each of the 8 external-service source beans (3 {@code ShowSource} +
 * 5 {@code RelationSource} implementations) is individually gated by its own
 * {@code setlistscout.sources.<id>} flag, mirroring {@link PollerConditionalWiringTest}'s
 * technique for the two poller beans -- but opt-OUT rather than opt-IN
 * ({@code matchIfMissing = true}): every source stays on with zero config, and disabling one
 * (the motivating incident: Bandsintown) is a single Render env var that doesn't touch any other
 * source. See {@code scan.ScanUnitRunner#run} / {@code expansion.ExpandUnitRunner#run}: a disabled
 * source's bean is simply absent from the injected {@code List<ShowSource>}/
 * {@code List<RelationSource>}, which the existing "unknown source" WARN-and-no-op path already
 * handles -- see {@code ScanUnitRunnerTest#disabledSourceIsNoOp} /
 * {@code ExpandUnitRunnerTest#shouldNoOpForDisabledSource} for that scenario pinned under a real
 * production source id.
 */
class SourceConditionalWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(TicketmasterService.class, () -> mock(TicketmasterService.class))
            .withBean(BandsintownService.class, () -> mock(BandsintownService.class))
            .withBean(BandSiteScraperService.class, () -> mock(BandSiteScraperService.class))
            .withBean(GeocodingService.class, () -> mock(GeocodingService.class))
            .withBean(MusicBrainzService.class, () -> mock(MusicBrainzService.class))
            .withBean(DiscogsService.class, () -> mock(DiscogsService.class))
            .withBean(LastFmService.class, () -> mock(LastFmService.class))
            .withBean(SimilarArtistLlmService.class, () -> mock(SimilarArtistLlmService.class))
            .withBean(TributeLlmService.class, () -> mock(TributeLlmService.class))
            // Same rationale as PollerConditionalWiringTest#PollerScanConfig: withUserConfiguration/
            // @Import both try to instantiate the imported class as a @Configuration the moment its
            // condition passes ("no default constructor found") since these are plain @Component
            // classes with real constructors to autowire. A type-filtered @ComponentScan is the
            // same registration path SpringBootApplication's implicit scan uses, so
            // @ConditionalOnProperty is evaluated exactly as in production.
            .withUserConfiguration(SourceScanConfig.class);

    @Configuration
    @ComponentScan(
            basePackageClasses = {
                    TicketmasterShowSource.class, BandsintownShowSource.class, BandSiteShowSource.class,
                    MusicBrainzRelationSource.class, DiscogsRelationSource.class, LastFmSimilarSource.class,
                    SimilarLlmSource.class, TributeLlmSource.class},
            useDefaultFilters = false,
            includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = {
                    TicketmasterShowSource.class, BandsintownShowSource.class, BandSiteShowSource.class,
                    MusicBrainzRelationSource.class, DiscogsRelationSource.class, LastFmSimilarSource.class,
                    SimilarLlmSource.class, TributeLlmSource.class}))
    static class SourceScanConfig {}

    /** (source bean class, its {@code setlistscout.sources.<id>} property key) -- the 8 pairs from issue #139. */
    static Stream<Arguments> sources() {
        return Stream.of(
                Arguments.of(TicketmasterShowSource.class, "setlistscout.sources.ticketmaster"),
                Arguments.of(BandsintownShowSource.class, "setlistscout.sources.bandsintown"),
                Arguments.of(BandSiteShowSource.class, "setlistscout.sources.band-site"),
                Arguments.of(MusicBrainzRelationSource.class, "setlistscout.sources.musicbrainz"),
                Arguments.of(DiscogsRelationSource.class, "setlistscout.sources.discogs"),
                Arguments.of(LastFmSimilarSource.class, "setlistscout.sources.lastfm"),
                Arguments.of(SimilarLlmSource.class, "setlistscout.sources.similar-llm"),
                Arguments.of(TributeLlmSource.class, "setlistscout.sources.tribute-llm"));
    }

    @ParameterizedTest(name = "{0} is present when {1} is unset (opt-out default)")
    @MethodSource("sources")
    void presentWhenPropertyUnset(Class<?> sourceClass, String propertyKey) {
        contextRunner.run(context -> assertThat(context).hasNotFailed().hasSingleBean(sourceClass));
    }

    @ParameterizedTest(name = "{0} is present when {1}=true")
    @MethodSource("sources")
    void presentWhenPropertyExplicitlyTrue(Class<?> sourceClass, String propertyKey) {
        contextRunner.withPropertyValues(propertyKey + "=true")
                .run(context -> assertThat(context).hasNotFailed().hasSingleBean(sourceClass));
    }

    @ParameterizedTest(name = "{0} is absent when {1}=false")
    @MethodSource("sources")
    void absentWhenPropertyFalse(Class<?> sourceClass, String propertyKey) {
        contextRunner.withPropertyValues(propertyKey + "=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(sourceClass));
    }
}
