package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.settings.GeocodingService;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.robsartin.setlistscout.support.CsvTestSupport.parseCsv;
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
    @DisplayName("issue #229: a non-admin can create a shared scan; it redirects there and both "
            + "participants see it via visibleTo -- even though OTHER already has a pairing (from "
            + "setUp), proving creation is no longer capped at one")
    void nonAdminCanCreateASharedScanAndBothParticipantsSeeIt() throws Exception {
        MvcResult result = mockMvc.perform(post("/shared")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER)))
                        .with(csrf())
                        .param("label", "David & Spencer").param("ownerB", SECOND_PARTNER))
                .andExpect(status().is3xxRedirection())
                .andReturn();

        String location = result.getResponse().getRedirectedUrl();
        assertThat(location).isNotNull().startsWith("/shared/");
        Long newId = Long.valueOf(location.substring(location.lastIndexOf('/') + 1));

        assertThat(service.visibleTo(OTHER)).extracting(SharedScan::getId).contains(newId);
        assertThat(service.visibleTo(SECOND_PARTNER)).extracting(SharedScan::getId).contains(newId);
    }

    @Test
    @DisplayName("issue #229: a non-admin with no pairing yet sees the create form, not just "
            + "an explanatory message")
    void nonAdminSeesCreateFormWhenTheyHaveNoPairing() throws Exception {
        String body = pageAs(STRANGER);

        assertThat(body).contains("id=\"shared-create-target\"");
        assertThat(body).doesNotContain("No shared scan has been set up yet.");
    }

    /**
     * Issue #229, Part A.1. Uses STRANGER (no pairing of their own in setUp -- scan == null for
     * them) rather than OTHER, so the only variable in play is the dropdown's email-exclusion
     * rule, not also Part B's separate "form renders when scan != null" fix. The precise
     * {@code <option value="...">...</option>} pattern matters: ADMIN's bare address legitimately
     * appears elsewhere on other callers' pages (e.g. the "otherParticipant" text), so a loose
     * {@code contains(ADMIN)} would prove nothing -- anchoring to the actual option tag is what
     * makes this a real assertion about the dropdown's contents. {@code NavModelAdviceTest} is the
     * isolated, unit-level proof of the same rule; this is the end-to-end confirmation through the
     * rendered page.
     */
    @Test
    @DisplayName("issue #229: the dropdown offered to a user excludes themselves and includes the admin")
    void dropdownExcludesCallerIncludesAdmin() throws Exception {
        String body = pageAs(STRANGER);

        assertThat(body).contains("<option value=\"" + ADMIN + "\">" + ADMIN + "</option>");
        assertThat(body).doesNotContain("<option value=\"" + STRANGER + "\">");
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
                new BigDecimal("42.50"), "ticketmaster", "https://example.test/tix", Show.Kind.MUSIC);
        show.setOwner(scan.getOwnerKey());
        showRepository.save(show);

        String body = pageAs(ADMIN);

        assertThat(body).contains("<th scope=\"col\">Date</th>");
        assertThat(body).contains("<th scope=\"col\">Artist</th>");
        assertThat(body).contains("Metro");
        assertThat(body).contains("class=\"table-scroll\"");
    }

    @Test
    @DisplayName("the user picker is a labelled select, for the create form")
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

    /**
     * Issue #229, Part B's regression test. {@code shared.html} used to wrap the ENTIRE create
     * form in {@code th:if="${scan == null}"} -- so once a caller had even one pairing, the form
     * vanished with no way to create a second. ADMIN already has a pairing from {@code setUp}
     * (scan != null for them), so this fails today for exactly that reason, independent of Parts A
     * and A.1: it's a GET-rendering assertion, not a POST, so it isn't sensitive to whether the
     * controller's admin gate is gone -- only to whether the template still nests the form inside
     * the scan-null block.
     */
    @Test
    @DisplayName("issue #229 Part B: the create form still renders once the caller already has a pairing")
    void createFormRendersWhenCallerAlreadyHasAPairing() throws Exception {
        String body = pageAs(ADMIN);

        assertThat(body).contains("id=\"shared-create-target\"");
        assertThat(body).contains("Create shared scan");
    }

    /**
     * Issue #229, Part B follow-up. The dropdown does not filter out addresses the caller is
     * already paired with (deliberately -- see the commit message / PR description: filtering it
     * would need a page-local model attribute distinct from the shared {@code otherOwnerEmails},
     * since that same attribute also drives the unrelated admin cross-account dropdowns on
     * shows.html/candidates.html). So now that the form stays visible once scan != null (this
     * commit), a user CAN select an already-paired address and submit. This proves that path is
     * already safe: SharedScanService#validateNewPairing (in place since #187/PR#188, independent
     * of this change) rejects it with 400 and writes no row -- already covered at the service
     * layer by SharedScanServiceTest#duplicatePairingIsRejectedInEitherDirection; this is the same
     * protection re-proven through the now-more-reachable controller path.
     */
    @Test
    @DisplayName("issue #229 Part B: selecting an already-paired address from the form is "
            + "rejected, not silently duplicated")
    void creatingADuplicatePairingViaTheFormIsRejected() throws Exception {
        long before = sharedScanRepository.count();

        mockMvc.perform(post("/shared")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("label", "Rob & David Again").param("ownerB", OTHER))
                .andExpect(status().isBadRequest());

        assertThat(sharedScanRepository.count()).isEqualTo(before);
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

    // ---- #229: removing the admin gate makes POST /shared reachable by every allow-listed user,
    // not just one trusted account -- so it is worth proving, AT THIS HTTP BOUNDARY, that a
    // crafted ownerB still cannot create a pairing against a non-existent/non-allow-listed address
    // or against the caller's own address. The actual guard is SharedScanService#validateNewPairing
    // (case-insensitive allow-list check + self-pairing check), already in place since #187/PR#188
    // -- SharedScanServiceTest#nonAllowListedOwnerBIsRejected and #selfPairingIsRejected already
    // cover it at the service layer. These two are the same protection re-proven through the
    // controller, specifically because the reachability of this endpoint is what #229 changes.

    @Test
    @DisplayName("issue #229: ownerB outside the allow-list is rejected and creates no row")
    void createRejectsOwnerBOutsideTheAllowlist() throws Exception {
        long before = sharedScanRepository.count();

        mockMvc.perform(post("/shared")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("label", "Rob & Nobody").param("ownerB", "nobody@example.com"))
                .andExpect(status().isBadRequest());

        assertThat(sharedScanRepository.count()).isEqualTo(before);
    }

    @Test
    @DisplayName("issue #229: pairing yourself as ownerB is rejected and creates no row")
    void createRejectsSelfPairing() throws Exception {
        long before = sharedScanRepository.count();

        mockMvc.perform(post("/shared")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("label", "Solo").param("ownerB", ADMIN))
                .andExpect(status().isBadRequest());

        assertThat(sharedScanRepository.count()).isEqualTo(before);
    }

    // ---- issue #228: GET /shared/{id}.csv ---------------------------------------------------

    @Test
    @DisplayName("issue #228: the shared-scan CSV returns text/csv with an attachment filename")
    void csvReturnsTextCsvWithAttachmentFilename() throws Exception {
        MockHttpServletResponse response = mockMvc.perform(get("/shared/" + scan.getId() + ".csv")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse();

        assertThat(response.getContentType()).startsWith("text/csv");
        assertThat(response.getHeader("Content-Disposition"))
                .isEqualTo("attachment; filename=\"shared-" + scan.getId() + ".csv\"");
    }

    @Test
    @DisplayName("issue #228: a non-participant gets 404 on the shared-scan CSV, exactly as they do for the page "
            + "(reuses SharedScanService#requireVisible, not a second access check)")
    void nonParticipantGets404ForSharedCsv() throws Exception {
        mockMvc.perform(get("/shared/" + scan.getId() + ".csv")
                        .with(oidcLogin().idToken(t -> t.claim("email", STRANGER))))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("issue #228: a non-participant gets 404 on a shared CSV for an id that belongs to a "
            + "DIFFERENT pairing they're not in either")
    void nonParticipantGets404ForASpecificPairingCsv() throws Exception {
        SharedScan second = service.create("Rob & Spencer", ADMIN, SECOND_PARTNER);

        mockMvc.perform(get("/shared/" + second.getId() + ".csv")
                        .with(oidcLogin().idToken(t -> t.claim("email", STRANGER))))
                .andExpect(status().isNotFound());
    }

    /**
     * Owner isolation, asserted by PARSING the CSV body rather than a raw string
     * {@code contains}/{@code doesNotContain} -- the brief's own cautionary tale is a test like
     * that passing vacuously (an HTML-escaped apostrophe made a literal-string assertion never
     * match either way, whether or not scoping actually worked). Parsing the artist_name column
     * and asserting the exact set leaves no such gap: the personal-account show can only be
     * absent if the query really is scoped to {@code scan.getOwnerKey()}.
     */
    @Test
    @DisplayName("issue #228: the shared-scan CSV contains exactly the pairing's own shows -- a show on "
            + "a participant's PERSONAL account (not the shared synthetic owner key) does not leak in")
    void csvContainsOnlySharedShowsNotAParticipantsPersonalOnes() throws Exception {
        Show sharedShow = new Show("The \"Legends\", Vol. 2", LocalDateTime.now().plusDays(5), "Metro", "Chicago",
                new BigDecimal("42.50"), "ticketmaster", "https://tix.example/1", Show.Kind.MUSIC);
        sharedShow.setOwner(scan.getOwnerKey());
        showRepository.save(sharedShow);
        Show personalShow = new Show("Solo Artist", LocalDateTime.now().plusDays(5), "Solo Venue", "Austin",
                null, "ticketmaster", null, Show.Kind.MUSIC);
        personalShow.setOwner(ADMIN);
        showRepository.save(personalShow);

        byte[] body = mockMvc.perform(get("/shared/" + scan.getId() + ".csv")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        List<CSVRecord> records = parseCsv(body);
        assertThat(records).extracting(r -> r.get("artist_name")).containsExactly("The \"Legends\", Vol. 2");
    }

    @Test
    @DisplayName("issue #228: a non-ASCII artist name in a shared show round-trips through the real endpoint")
    void csvNonAsciiNameRoundTrips() throws Exception {
        Show s = new Show("Øystein Sevåg", LocalDateTime.now().plusDays(5), "Metro", "Chicago",
                null, "ticketmaster", null, Show.Kind.MUSIC);
        s.setOwner(scan.getOwnerKey());
        showRepository.save(s);

        byte[] body = mockMvc.perform(get("/shared/" + scan.getId() + ".csv")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();

        List<CSVRecord> records = parseCsv(body);
        assertThat(records.get(0).get("artist_name")).isEqualTo("Øystein Sevåg");
    }
}
