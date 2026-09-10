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
 * Issue #273's headline case, end to end: a source switched OFF with
 * {@code setlistscout.sources.bandsintown=false} (#139) must appear on the card labelled {@code Off}
 * and must NOT be labelled {@code Down} -- even though its stored health row says unhealthy.
 *
 * <p>Its own class because that flag has to be set before the context boots: the property is what
 * drops the {@code ShowSource} bean, and it is the absence of that bean this whole case turns on. A
 * disabled source can never clear a stale unhealthy row (with nothing calling it, it can record
 * neither a failure nor a success), so reporting it as Down would describe a source that is not
 * being called at all -- and would make the one genuinely alarming state less credible.
 *
 * <p>This is production's current state, not a hypothetical: Bandsintown is switched off right now,
 * precisely because it was returning 403 to everything (#265).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@TestPropertySource(properties = "setlistscout.sources.bandsintown=false")
class AdminSourceHealthDisabledSourceRenderTest extends AbstractPostgresIntegrationTest {

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

    @Test
    @DisplayName("issue #273: a DISABLED source renders as Off and never as Down, even with a "
            + "stored unhealthy row it could never clear")
    void aDisabledSourceRendersOffNotDown() throws Exception {
        for (long artistId = 1; artistId <= 50; artistId++) {
            sourceHealth.recordFailure("bandsintown", artistId,
                    HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));
        }

        mockMvc.perform(get("/admin/queues")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().isOk())
                // It is still LISTED -- vanishing the moment someone disables it is the state
                // nothing in the app reported before this card existed.
                .andExpect(content().string(containsString("bandsintown")))
                .andExpect(content().string(containsString("state-off")))
                .andExpect(content().string(not(containsString("state-down"))));
    }

    @Test
    @DisplayName("issue #273: and the Shows banner stays quiet for it too -- #265 already treats a "
            + "disabled source as healthy, and the two views must not disagree")
    void theShowsBannerAlsoStaysQuiet() throws Exception {
        for (long artistId = 1; artistId <= 50; artistId++) {
            sourceHealth.recordFailure("bandsintown", artistId,
                    HttpClientErrorException.create(HttpStatus.FORBIDDEN, "Forbidden", null, null, null));
        }

        mockMvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("source-down"))));
    }
}
