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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #273: the Source health card actually RENDERS.
 *
 * <p>A Thymeleaf expression error compiles perfectly and 500s at runtime, and the whole point of
 * this card is to be the place someone looks -- a model attribute that never reaches the page would
 * be worth nothing. Drives the real template through MockMvc, including {@code #temporals.format} on
 * an {@code Instant} and the {@code src.hasStreak()} call.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminSourceHealthCardRenderTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ADMIN_EMAIL = "rob.sartin@gmail.com";

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

    private void fail(String source, long artists) {
        for (long artistId = 1; artistId <= artists; artistId++) {
            sourceHealth.recordFailure(source, artistId,
                    HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));
        }
    }

    private org.springframework.test.web.servlet.ResultActions page() throws Exception {
        return mockMvc.perform(get("/admin/queues")
                .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))));
    }

    @Test
    @DisplayName("issue #273: the card lists every enabled source, including ones that have never "
            + "run -- 'is it working?' must be answerable when the answer is yes")
    void listsEveryEnabledSourceIncludingUnrunOnes() throws Exception {
        page().andExpect(status().isOk())
                .andExpect(content().string(containsString("Source health")))
                .andExpect(content().string(containsString("ticketmaster")))
                .andExpect(content().string(containsString("band-site")))
                .andExpect(content().string(containsString("never")));
    }

    @Test
    @DisplayName("issue #273: a source past the threshold renders as Down, with its streak signature")
    void aDeadSourceRendersDown() throws Exception {
        fail("ticketmaster", 50);

        page().andExpect(status().isOk())
                .andExpect(content().string(containsString("state-down")))
                .andExpect(content().string(containsString("403")));
    }

    @Test
    @DisplayName("issue #273: a source MID-STREAK renders its streak while still reading On -- the "
            + "moment an operator would most want to look")
    void aMidStreakSourceRendersItsStreak() throws Exception {
        fail("ticketmaster", 3);

        page().andExpect(status().isOk())
                .andExpect(content().string(containsString("3 x 403")))
                .andExpect(content().string(containsString("state-on")));
    }

    @Test
    @DisplayName("issue #273: the card is read-only -- no clear/reset control, because #265's "
            + "recovery is automatic and a manual step is what caused the two-week outage")
    void theCardOffersNoControls() throws Exception {
        fail("ticketmaster", 50);

        page().andExpect(status().isOk())
                .andExpect(content().string(containsString("there is nothing to clear here")));
    }
}
