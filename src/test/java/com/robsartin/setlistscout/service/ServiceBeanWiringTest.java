package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.expansion.DiscogsService;
import com.robsartin.setlistscout.expansion.LastFmService;
import com.robsartin.setlistscout.expansion.SimilarArtistLlmService;
import com.robsartin.setlistscout.expansion.TributeLlmService;
import com.robsartin.setlistscout.scan.BandsintownService;
import com.robsartin.setlistscout.scan.TicketmasterService;
import com.robsartin.setlistscout.shared.MusicBrainzService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards against the "app can't start" class of bug: every RestClient-backed service
 * has a package-private test-seam constructor alongside its production one, and Spring
 * only auto-selects a constructor when there is exactly one. Without an explicit
 * @Autowired on the production constructor, Spring falls back to a non-existent no-arg
 * constructor and context startup fails. This test instantiates each service as a bean
 * (no DB, no web server needed) so that regression fails the build instead of the deploy.
 */
class ServiceBeanWiringTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withBean(AppProperties.class, TestAppProperties::withKeys)
            .withBean(MusicBrainzService.class)
            .withBean(DiscogsService.class)
            .withBean(LastFmService.class)
            .withBean(SimilarArtistLlmService.class)
            .withBean(TributeLlmService.class)
            .withBean(BandsintownService.class)
            .withBean(TicketmasterService.class);

    @Test
    @DisplayName("every RestClient service instantiates as a Spring bean")
    void allRestClientServicesInstantiateAsBeans() {
        contextRunner.run(context -> assertThat(context)
                .hasNotFailed()
                .hasSingleBean(MusicBrainzService.class)
                .hasSingleBean(DiscogsService.class)
                .hasSingleBean(LastFmService.class)
                .hasSingleBean(SimilarArtistLlmService.class)
                .hasSingleBean(TributeLlmService.class)
                .hasSingleBean(BandsintownService.class)
                .hasSingleBean(TicketmasterService.class));
    }
}
