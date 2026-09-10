package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.scan.SourceHealthRepository;
import com.robsartin.setlistscout.scan.SourceHealthService;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #265: the banner actually RENDERS.
 *
 * <p>The whole issue is that a dead source was invisible everywhere a person looks, so a model
 * attribute that never reaches the page would fix nothing. This drives the real template through
 * MockMvc -- {@code #temporals.format} on an {@code Instant}, the pluralised heading, the singular
 * case -- because a Thymeleaf expression error is a 500 at runtime and compiles perfectly.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = {
        "setlistscout.scan-poller-enabled=false",
        "setlistscout.expand-poller-enabled=false",
        "setlistscout.job-backfill-enabled=false"
})
class ShowsSourceDownBannerRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String OWNER = "source-down-render@example.com";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SourceHealthService sourceHealth;

    @Autowired
    private SourceHealthRepository sourceHealthRepository;

    @BeforeEach
    void setUp() {
        sourceHealthRepository.deleteAll();
        sourceHealth.refresh();
    }

    /**
     * Enough same-signature failures across enough distinct artists to trip the threshold. Uses a
     * generous count rather than reading the constant, which is package-private to {@code scan}:
     * this test lives in {@code web} on purpose, exercising the page the way a browser would.
     */
    private void killSource(String source) {
        for (long artistId = 1; artistId <= 50; artistId++) {
            sourceHealth.recordFailure(source, artistId,
                    HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));
        }
    }

    @Test
    @DisplayName("issue #265: a dead source renders a banner on the Shows page, naming the source "
            + "and its failure signature")
    void deadSourceRendersTheBanner() throws Exception {
        killSource("bandsintown");

        mockMvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("source-down")))
                .andExpect(content().string(containsString("A show source has stopped responding")))
                .andExpect(content().string(containsString("bandsintown")))
                .andExpect(content().string(containsString("403")))
                .andExpect(content().string(containsString("there is nothing to click")));
    }

    @Test
    @DisplayName("issue #265: every source healthy renders NO banner -- it must not be background "
            + "noise on a normal day, or it stops being read")
    void healthyRendersNoBanner() throws Exception {
        mockMvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("source-down"))))
                .andExpect(content().string(not(containsString("stopped responding"))));
    }

    @Test
    @DisplayName("issue #265: two dead sources render the plural heading and both names")
    void twoDeadSourcesRenderPlural() throws Exception {
        killSource("bandsintown");
        killSource("ticketmaster");

        mockMvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Show sources have stopped responding")))
                .andExpect(content().string(containsString("bandsintown")))
                .andExpect(content().string(containsString("ticketmaster")));
    }

    /**
     * Source health is global by design (one shared credential per provider), so a second owner
     * must see the identical banner. This is the counterpart to the issue's owner-scoping
     * requirement: it is asserted, and asserted to be GLOBAL rather than quietly per-owner.
     */
    @Test
    @DisplayName("issue #265: a different owner sees the same banner -- health is global, because "
            + "one shared credential fails for everybody at once")
    void anotherOwnerSeesTheSameBanner() throws Exception {
        killSource("bandsintown");

        mockMvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", "someone-else@example.com"))))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("bandsintown")));
    }
}
