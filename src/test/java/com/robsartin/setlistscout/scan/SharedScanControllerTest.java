package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class SharedScanControllerTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final String ADMIN = "rob@example.com";
    private static final String OTHER = "david@example.com";
    private static final String STRANGER = "stranger@example.com";

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("setlistscout.auth.admin-email", () -> ADMIN);
        registry.add("setlistscout.auth.allowed-emails", () -> ADMIN + "," + OTHER + "," + STRANGER);
    }

    /**
     * Deviation from the brief (documented in task-6-report.md): the brief's
     * noLocationIsItsOwnState assumes a freshly created scan's settings have no geocode because
     * the test environment has no network access to Zippopotam.us. That's not true here -- this
     * sandbox reaches the real API, and it geocodes the app's real default ZIP (78701) successfully,
     * which made the "no location yet" state never render. "00000" is the same known-invalid ZIP
     * SettingsServiceTest already uses as its geocode-failure fixture: GeocodingService#fetch
     * degrades to Optional.empty() on ANY error, so this 404s against the real API and equally
     * fails closed with no network at all -- hermetic either way, instead of accidentally
     * depending on which one this run happens to have.
     */
    @DynamicPropertySource
    static void geocodingProperties(DynamicPropertyRegistry registry) {
        registry.add("setlistscout.defaults.postal-code", () -> "00000");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private SharedScanService service;
    @Autowired private SharedScanRepository sharedScanRepository;
    @Autowired private ShowRepository showRepository;

    private SharedScan scan;

    @BeforeEach
    void setUp() {
        showRepository.deleteAll();
        sharedScanRepository.deleteAll();
        scan = service.create("Rob & David", ADMIN, OTHER);
    }

    private String pageAs(String email) throws Exception {
        return mockMvc.perform(get("/shared").with(oidcLogin().idToken(t -> t.claim("email", email))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    @DisplayName("both participants can open the page")
    void bothParticipantsSeeIt() throws Exception {
        assertThat(pageAs(ADMIN)).contains("Rob &amp; David");
        assertThat(pageAs(OTHER)).contains("Rob &amp; David");
    }

    @Test
    @DisplayName("a non-participant sees no shared scan, not someone else's")
    void nonParticipantSeesNothing() throws Exception {
        assertThat(pageAs(STRANGER)).doesNotContain("Rob &amp; David");
    }

    @Test
    @DisplayName("only the admin may create a shared scan")
    void onlyAdminCanCreate() throws Exception {
        mockMvc.perform(post("/shared")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER)))
                        .with(csrf())
                        .param("label", "Sneaky").param("ownerB", STRANGER))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("a non-participant cannot change another pairing's settings")
    void nonParticipantCannotEditSettings() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/settings")
                        .with(oidcLogin().idToken(t -> t.claim("email", STRANGER)))
                        .with(csrf())
                        .param("postalCode", "60601").param("radiusMiles", "25").param("monthsAhead", "3"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("a participant can set the location, and it is stored against the shared key")
    void participantCanSetLocation() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/settings")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER)))
                        .with(csrf())
                        .param("postalCode", "60601").param("radiusMiles", "25").param("monthsAhead", "3"))
                .andExpect(status().is3xxRedirection());

        assertThat(service.settingsFor(scan).getRadiusMiles()).isEqualTo(25);
        assertThat(service.settingsFor(scan).getMonthsAhead()).isEqualTo(3);
    }

    @Test
    @DisplayName("shows render in a semantic table with column headers")
    void showsRenderInASemanticTable() throws Exception {
        Show show = new Show("Tom Petty", LocalDateTime.now().plusDays(10), "Metro", "Chicago",
                new BigDecimal("42.50"), "ticketmaster", "https://example.test/tix");
        show.setOwner(scan.getOwnerKey());
        showRepository.save(show);

        String body = pageAs(ADMIN);

        assertThat(body).contains("<th scope=\"col\">Date</th>");
        assertThat(body).contains("<th scope=\"col\">Artist</th>");
        assertThat(body).contains("Metro");
        assertThat(body).contains("class=\"table-scroll\"");
    }

    @Test
    @DisplayName("the user picker is a labelled select, for the admin's create form")
    void createFormPickerIsLabelled() throws Exception {
        sharedScanRepository.deleteAll();
        String body = pageAs(ADMIN);

        assertThat(body).contains("<label for=\"shared-create-target\"");
        assertThat(body).contains("id=\"shared-create-target\"");
    }

    @Test
    @DisplayName("'no location yet' is its own message, not an empty show list")
    void noLocationIsItsOwnState() throws Exception {
        // A freshly created scan has settings but no geocode until a ZIP is saved.
        String body = pageAs(ADMIN);

        assertThat(body).contains("Set a location above");
        assertThat(body).doesNotContain("don't follow any of the same artists");
    }

    @Test
    @DisplayName("'no artists in common' and 'nothing playing there' are different messages")
    void emptyStatesDoNotCollapse() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/settings")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("postalCode", "60601").param("radiusMiles", "25").param("monthsAhead", "3"))
                .andExpect(status().is3xxRedirection());

        // No artists seeded for either participant, so the intersection is genuinely empty --
        // which must NOT read as "we searched and found nothing".
        String body = pageAs(ADMIN);
        assertThat(body).contains("don't follow any of the same artists");
        assertThat(body).doesNotContain("but none of them are");
    }
}
