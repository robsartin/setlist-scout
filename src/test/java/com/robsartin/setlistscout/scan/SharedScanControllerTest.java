package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.settings.GeocodingService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
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
    /** #187: a second pairing's other participant -- kept distinct from OTHER/STRANGER so the
     * "two pairings" tests read clearly and STRANGER can stay a true outsider to both. */
    private static final String SECOND_PARTNER = "spencer@example.com";

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("setlistscout.auth.admin-email", () -> ADMIN);
        registry.add("setlistscout.auth.allowed-emails",
                () -> ADMIN + "," + OTHER + "," + STRANGER + "," + SECOND_PARTNER);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private SharedScanService service;
    @Autowired private SharedScanRepository sharedScanRepository;
    @Autowired private ShowRepository showRepository;

    /**
     * Replaces the real Zippopotam.us-backed bean so this class never makes a live HTTP call
     * (finding 1 of the 2026-08-16 whole-branch review). Stubbed empty by default in
     * {@link #setUp()} -- the "no geocode yet" state most of this class's fixtures need -- and
     * re-stubbed to a real result in the one test that needs a successful geocode. Same pattern as
     * {@code JobEnqueueFlowTest}/{@code PollerFlowTest}.
     */
    @MockitoBean private GeocodingService geocodingService;

    private SharedScan scan;

    @BeforeEach
    void setUp() {
        showRepository.deleteAll();
        sharedScanRepository.deleteAll();
        when(geocodingService.geocode(any())).thenReturn(Optional.empty());
        scan = service.create("Rob & David", ADMIN, OTHER);
    }

    private String pageAs(String email) throws Exception {
        return mockMvc.perform(get("/shared").with(oidcLogin().idToken(t -> t.claim("email", email))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    /** Same as {@link #pageAs}, but for a specific pairing (#187's {@code GET /shared/{id}}). */
    private String pageForId(Long id, String email) throws Exception {
        return mockMvc.perform(get("/shared/" + id).with(oidcLogin().idToken(t -> t.claim("email", email))))
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
    @DisplayName("the create form has double-submit protection, matching the app's established htmx pattern")
    void createFormHasDoubleSubmitProtection() throws Exception {
        sharedScanRepository.deleteAll();
        String body = pageAs(ADMIN);

        assertThat(body).contains("hx-disabled-elt=\"find button\"");
    }

    @Test
    @DisplayName("an htmx create request returns the content fragment, not a redirect")
    void htmxCreateReturnsTheContentFragment() throws Exception {
        sharedScanRepository.deleteAll();

        String body = mockMvc.perform(post("/shared")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .header("HX-Request", "true")
                        .param("label", "Rob & Stranger").param("ownerB", STRANGER))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("Rob &amp; Stranger");
        assertThat(body).doesNotContain("<head");
        // The create form's submit button (whatever was focused before the swap) is gone from
        // this response -- the fragment root must claim focus itself, or it silently drops to
        // <body> (the same outerHTML-swap gotcha shows.html's showsRegion guards against).
        assertThat(body).containsPattern("id=\"shared-content\"[^>]*autofocus");
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
        // This path needs settings.latitude != null (shared.html line ~64), unlike every other
        // test in this class -- stub a successful geocode just for this one.
        when(geocodingService.geocode(any()))
                .thenReturn(Optional.of(new GeocodingService.GeoResult(41.8781, -87.6298, "Chicago", "IL")));

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

    // ---- #187: two pairings. Every test above uses exactly one, so none of them can catch
    // scans.get(0) picking an arbitrary, unstable pairing and leaving the other unreachable.

    @Test
    @DisplayName("both pairings are reachable, each at its own URL")
    void bothPairingsAreReachable() throws Exception {
        SharedScan second = service.create("Rob & Spencer", ADMIN, SECOND_PARTNER);

        assertThat(pageForId(scan.getId(), ADMIN)).contains("Rob &amp; David");
        assertThat(pageForId(second.getId(), ADMIN)).contains("Rob &amp; Spencer");
    }

    @Test
    @DisplayName("the default page load is stable across repeated requests")
    void defaultPageIsStableAcrossRepeatedLoads() throws Exception {
        service.create("Rob & Spencer", ADMIN, SECOND_PARTNER);

        assertThat(pageAs(ADMIN)).contains("Rob &amp; David");
        assertThat(pageAs(ADMIN)).contains("Rob &amp; David");
        assertThat(pageAs(ADMIN)).contains("Rob &amp; David");
    }

    @Test
    @DisplayName("a non-participant gets 404 for a specific pairing they are not in")
    void nonParticipantGets404ForASpecificPairing() throws Exception {
        service.create("Rob & Spencer", ADMIN, SECOND_PARTNER);

        mockMvc.perform(get("/shared/" + scan.getId())
                        .with(oidcLogin().idToken(t -> t.claim("email", STRANGER))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the picker to the other pairing is a labelled control")
    void pickerToOtherPairingIsALabelledControl() throws Exception {
        SharedScan second = service.create("Rob & Spencer", ADMIN, SECOND_PARTNER);

        String body = pageAs(ADMIN);

        assertThat(body).contains("aria-label=\"Other shared scans\"");
        assertThat(body).contains("/shared/" + second.getId());
        assertThat(body).contains("Rob &amp; Spencer");
    }

    @Test
    @DisplayName("two pairings keep their own distinct empty state -- one does not bleed into the other")
    void emptyStatesStayDistinctAcrossPairings() throws Exception {
        // `scan` (Rob & David) stays in its default "no location set" state -- geocode is
        // stubbed empty in setUp().
        SharedScan second = service.create("Rob & Spencer", ADMIN, SECOND_PARTNER);
        when(geocodingService.geocode(any()))
                .thenReturn(Optional.of(new GeocodingService.GeoResult(41.8781, -87.6298, "Chicago", "IL")));
        mockMvc.perform(post("/shared/" + second.getId() + "/settings")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("postalCode", "60601").param("radiusMiles", "25").param("monthsAhead", "3"))
                .andExpect(status().is3xxRedirection());

        String firstBody = pageForId(scan.getId(), ADMIN);
        assertThat(firstBody).contains("Set a location above");
        assertThat(firstBody).doesNotContain("don't follow any of the same artists");

        String secondBody = pageForId(second.getId(), ADMIN);
        assertThat(secondBody).contains("don't follow any of the same artists");
        assertThat(secondBody).doesNotContain("Set a location above");
    }

    @Test
    @DisplayName("acting on a non-default pairing keeps that same pairing in view after the redirect")
    void actingOnANonDefaultPairingStaysThere() throws Exception {
        SharedScan second = service.create("Rob & Spencer", ADMIN, SECOND_PARTNER);

        mockMvc.perform(post("/shared/" + second.getId() + "/settings")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("postalCode", "60601").param("radiusMiles", "25").param("monthsAhead", "3"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/shared/" + second.getId()));
    }
}
