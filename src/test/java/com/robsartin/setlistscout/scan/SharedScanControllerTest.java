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
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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

    /**
     * #235: was "shows render in a semantic table with column headers", pinning a literal
     * {@code <table>} with {@code <th scope="col">Date</th>}/{@code <th scope="col">Artist</th>}
     * and {@code class="table-scroll"}. Issue #235 deliberately replaces that table with the same
     * {@code .gig}/date-rail listing shows.html uses -- its own system description covers "the
     * shows views" (plural), and shared.html shows exactly that: a chronological list of dated
     * shows. The table markup this test used to pin no longer exists anywhere on the page; this is
     * not a stale-test workaround, it is re-pointing the assertion at the new markup that replaced
     * it, so the test still proves the thing it always cared about -- shows render as real
     * structured, accessible markup, not a blob of text -- against the new shape.
     */
    @Test
    @DisplayName("#235: shows render as accessible gig cards with a semantic time element")
    void showsRenderAsAccessibleGigCards() throws Exception {
        Show show = new Show("Tom Petty", LocalDateTime.now().plusDays(10), "Metro", "Chicago",
                new BigDecimal("42.50"), "ticketmaster", "https://example.test/tix", Show.Kind.MUSIC);
        show.setOwner(scan.getOwnerKey());
        showRepository.save(show);

        String body = pageAs(ADMIN);

        assertThat(body).contains("<time class=\"when\"");
        assertThat(body).contains("class=\"act\"");
        assertThat(body).contains("Tom Petty");
        assertThat(body).contains("Metro");
        assertThat(body).contains("class=\"month-rule\"");
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

    // ---- issue #238: renaming a shared scan's label. Mirrors POST /shared/{id}/settings exactly
    // -- resolves through requireVisible, redirects back to THIS pairing -- so a non-participant
    // 404s the same way settings/scan-now already do, and acting on a non-default pairing doesn't
    // bounce the page elsewhere.

    @Test
    @DisplayName("issue #238: either participant can rename the pairing")
    void eitherParticipantCanRename() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/label")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER)))
                        .with(csrf())
                        .param("label", "R&S"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/shared/" + scan.getId()));

        assertThat(service.requireVisible(ADMIN, scan.getId()).getLabel()).isEqualTo("R&S");
    }

    @Test
    @DisplayName("issue #238: a non-participant cannot rename another pairing's label, and it is unchanged")
    void nonParticipantCannotRename() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/label")
                        .with(oidcLogin().idToken(t -> t.claim("email", STRANGER)))
                        .with(csrf())
                        .param("label", "Hijacked"))
                .andExpect(status().isNotFound());

        assertThat(service.requireVisible(ADMIN, scan.getId()).getLabel()).isEqualTo("Rob & David");
    }

    @Test
    @DisplayName("issue #238: a blank or whitespace-only label is rejected, and it is unchanged")
    void blankLabelIsRejectedAndUnchanged() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/label")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("label", "   "))
                .andExpect(status().isBadRequest());

        assertThat(service.requireVisible(ADMIN, scan.getId()).getLabel()).isEqualTo("Rob & David");
    }

    /**
     * Issue #238's own highlighted risk: the real wanted names (R&amp;D, R&amp;S) contain a
     * literal ampersand. Thymeleaf's th:text escapes it exactly ONCE when rendering: the raw
     * stored label "R&D" becomes the response body "R&amp;D", which every browser decodes back to
     * "R&D" on screen -- the same mechanism already proven for "Rob &amp; David" elsewhere in this
     * class (see {@link #bothParticipantsSeeIt}). A careless implementation that ALSO
     * HTML-escapes the label before persisting it (redundant "sanitizing" on the way in, on top
     * of what Thymeleaf already does on the way out) makes it escape TWICE: the stored
     * "R&amp;D" renders as "R&amp;amp;D" in the response, which a browser decodes only ONE level
     * to the LITERAL VISIBLE TEXT "R&amp;D" on the page -- exactly the bug this issue calls out.
     * Asserting the precise single-escaped substring catches both failure modes at once: it fails
     * outright if escaping is skipped entirely (raw "R&D", no entity, substring absent) and fails
     * to match inside "R&amp;amp;D" if doubled (proven explicitly by the doesNotContain below).
     */
    @Test
    @DisplayName("issue #238: a renamed label containing '&' survives Thymeleaf's escaping exactly once")
    void renamedLabelWithAmpersandSurvivesEscapingExactlyOnce() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/label")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("label", "R&D"))
                .andExpect(status().is3xxRedirection());

        String body = pageForId(scan.getId(), ADMIN);

        assertThat(body).contains("R&amp;D");
        assertThat(body).doesNotContain("R&amp;amp;D");
    }

    /**
     * Proves the persisted rename shows up everywhere the label renders, not just in the stored
     * value: on the renamed pairing's own page (the page-sub line under the h1 -- scan.label's
     * only rendering there), AND from a DIFFERENT pairing's "Switch pairing" picker, which links
     * to this one by id and renders its label fresh from the DB on every load.
     */
    @Test
    @DisplayName("issue #238: a rename is reflected on the pairing's own page and in the picker from another pairing")
    void renameUpdatesLabelEverywhereItRenders() throws Exception {
        SharedScan second = service.create("Rob & Spencer", ADMIN, SECOND_PARTNER);

        mockMvc.perform(post("/shared/" + scan.getId() + "/label")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("label", "R&D"))
                .andExpect(status().is3xxRedirection());

        String ownPage = pageForId(scan.getId(), ADMIN);
        assertThat(ownPage).contains("R&amp;D");

        String otherPairingPage = pageForId(second.getId(), ADMIN);
        assertThat(otherPairingPage).contains("aria-label=\"Other shared scans\"");
        assertThat(otherPairingPage).contains("R&amp;D");
        assertThat(otherPairingPage).contains("/shared/" + scan.getId());
    }

    @Test
    @DisplayName("issue #238: the rename control is a labelled input, inside the scope details")
    void renameControlIsALabelledControl() throws Exception {
        String body = pageForId(scan.getId(), ADMIN);

        assertThat(body).contains("<label for=\"shared-label\"");
        assertThat(body).contains("id=\"shared-label\"");
    }

    @Test
    @DisplayName("issue #238: renaming a non-default pairing keeps that same pairing in view after the redirect")
    void renamingANonDefaultPairingStaysThere() throws Exception {
        SharedScan second = service.create("Rob & Spencer", ADMIN, SECOND_PARTNER);

        mockMvc.perform(post("/shared/" + second.getId() + "/label")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("label", "R&S"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/shared/" + second.getId()));
    }

    /**
     * Issue #155's invariant, re-proven for this new action (#238): 22 other tests already pin
     * this app-wide. The rename POST redirects to a plain GET -- a full page render, same as
     * every other non-htmx action on this page -- which carries NO autofocus at all;
     * {@code justCreated} is only ever set true by {@code #create}'s htmx branch.
     */
    @Test
    @DisplayName("issue #238: renaming does not disturb the single-autofocus-per-response invariant (#155)")
    void renameKeepsTheAutofocusInvariant() throws Exception {
        mockMvc.perform(post("/shared/" + scan.getId() + "/label")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN)))
                        .with(csrf())
                        .param("label", "R&D"))
                .andExpect(status().is3xxRedirection());

        String body = pageForId(scan.getId(), ADMIN);
        assertThat(countAutofocusElements(body)).isZero();
    }

    /**
     * Issue #241. The create form used to sit always-visible above the shows list, directly under
     * the pairing's own description -- so the page showed two "Name" inputs and the more prominent
     * one created a NEW pairing rather than renaming the current one. It now lives in a third
     * {@code <details>} beside "Switch pairing" and "Change scope".
     *
     * <p>Asserted structurally rather than by string position: {@code contains("<details>")}
     * would pass on a page where the form sits nowhere near a disclosure, and a substring-index
     * comparison would silently start passing for the wrong reason the moment the markup moves.
     * {@code closest("details")} asks the question the issue actually asks -- is this control
     * behind a disclosure -- and cannot be satisfied by accident.
     */
    @Test
    @DisplayName("issue #241: with a pairing selected, the create form is inside a disclosure")
    void createFormIsBehindADisclosureWhenCallerHasAPairing() throws Exception {
        Document doc = Jsoup.parse(pageForId(scan.getId(), ADMIN));

        Element target = doc.getElementById("shared-create-target");
        assertThat(target).as("create form must still be in the DOM (#229), not removed").isNotNull();
        assertThat(target.closest("details"))
                .as("create form must be behind a disclosure, not always-visible")
                .isNotNull();
    }

    /**
     * Issue #241's explicit counter-case. #229 exists because this form was once gated on
     * {@code scan == null} and vanished once you had a pairing; the fix for #241 must not
     * reintroduce that by burying it. And with no pairing at all it is the ONLY action on the
     * page, so collapsing it there would be worse than the bug being fixed.
     */
    @Test
    @DisplayName("issue #241: with no pairing, the create form is not buried in a disclosure")
    void createFormStaysProminentWhenThereIsNoPairing() throws Exception {
        sharedScanRepository.deleteAll();
        Document doc = Jsoup.parse(pageAs(ADMIN));

        Element target = doc.getElementById("shared-create-target");
        assertThat(target).isNotNull();
        assertThat(target.closest("details"))
                .as("the only action on a pairing-less page must not be collapsed")
                .isNull();
    }

    /**
     * Issue #241: the new disclosure is a sibling of the two the scope bar already carries, not a
     * separate section elsewhere on the page -- that co-location is the whole point of the move.
     */
    @Test
    @DisplayName("issue #241: the new-pairing disclosure sits beside the other scope disclosures")
    void newPairingDisclosureSitsInTheScopeEditCorner() throws Exception {
        Document doc = Jsoup.parse(pageForId(scan.getId(), ADMIN));

        // "Switch pairing" is deliberately absent: it renders only when otherScans is non-empty,
        // and ADMIN has a single pairing here. The two asserted below always render together.
        assertThat(doc.select(".edit > details > summary"))
                .extracting(Element::text)
                .contains("Change scope", "New pairing");
    }

    /**
     * Issue #241's headline symptom, pinned directly: the page must not offer an always-visible
     * "Name" box while a pairing is selected. The only editable label field is the rename input
     * inside "Change scope" (#238) and the create input inside "New pairing" -- both behind
     * disclosures. The current pairing's name appears as text, not as an input.
     */
    @Test
    @DisplayName("issue #241: no editable name field sits outside a disclosure when a pairing is selected")
    void noNameInputOutsideADisclosureWhenAPairingIsSelected() throws Exception {
        Document doc = Jsoup.parse(pageForId(scan.getId(), ADMIN));

        assertThat(doc.select("input[name=label]").stream()
                .filter(input -> input.closest("details") == null)
                .toList())
                .as("every name input must be behind a disclosure; the current name shows as text")
                .isEmpty();
        assertThat(doc.text()).contains(scan.getLabel());
    }

    /**
     * Issue #241 guards the ids the create flow is pinned on. Relocating the form must not rename
     * anything -- {@code SharedScanControllerTest}'s existing create tests assert these same ids
     * unedited, and this is the one place that says WHY they must not move.
     */
    @Test
    @DisplayName("issue #241: relocating the create form keeps its ids, action and htmx guards")
    void relocatedCreateFormKeepsItsContract() throws Exception {
        Document doc = Jsoup.parse(pageForId(scan.getId(), ADMIN));

        Element form = doc.getElementById("shared-create-target").closest("form");
        assertThat(form).isNotNull();
        assertThat(form.attr("action")).isEqualTo("/shared");
        assertThat(form.attr("method")).isEqualToIgnoringCase("post");
        assertThat(form.attr("hx-disabled-elt")).isEqualTo("find button");
        assertThat(form.select("label[for=shared-create-label]")).isNotEmpty();
        assertThat(form.select("label[for=shared-create-target]")).isNotEmpty();
    }
}
