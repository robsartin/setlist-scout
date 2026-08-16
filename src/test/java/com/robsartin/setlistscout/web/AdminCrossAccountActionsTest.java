package com.robsartin.setlistscout.web;

import com.robsartin.setlistscout.expansion.ExpandJob;
import com.robsartin.setlistscout.expansion.ExpandJobRepository;
import com.robsartin.setlistscout.scan.ScanJob;
import com.robsartin.setlistscout.scan.ScanJobRepository;
import com.robsartin.setlistscout.shared.JobStatus;
import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the admin-only cross-account "Scan now" / "Run expansion now" escape hatch (#136)
 * against a booted context + Postgres, signed in as test users. The security-rejection tests are
 * the point of this class: a non-admin hitting either admin endpoint with someone else's email as
 * the target must be rejected outright and must never touch that owner's jobs, even silently. The
 * admin-success tests drive the real redueAll mechanism (no mocks) to prove the happy path also
 * genuinely works, not just that the gate blocks. Runs in CI (needs Docker).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminCrossAccountActionsTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // The default from application.yml (setlistscout.auth.admin-email) -- nothing in this test
    // suite overrides it (src/test/resources/application.properties only turns the pollers off),
    // so this is genuinely "the admin" for every Testcontainers test that boots a real context.
    private static final String ADMIN_EMAIL = "rob.sartin@gmail.com";

    // application.yml's default ALLOWED_EMAILS is just the admin alone, so otherOwnerEmails
    // (NavModelAdvice) would always be empty and the admin dropdown would never actually render
    // in this test suite. Override it here to a real two-user list -- the David scenario the
    // whole issue (#136) exists for -- so the dropdown's th:each genuinely gets exercised, not
    // just short-circuited away by an empty list.
    private static final String OTHER_ALLOWED_EMAIL = "other-allowed-user@example.com";

    @DynamicPropertySource
    static void allowTwoUsers(DynamicPropertyRegistry registry) {
        registry.add("setlistscout.auth.allowed-emails", () -> ADMIN_EMAIL + "," + OTHER_ALLOWED_EMAIL);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ScanJobRepository scanJobRepository;

    @Autowired
    private ExpandJobRepository expandJobRepository;

    private ScanJob seedScanJob(String owner) {
        ScanJob job = new ScanJob(1L, "ticketmaster", JobStatus.FAILED, 3,
                Instant.now().plus(Duration.ofDays(14)));
        job.setOwner(owner);
        return scanJobRepository.saveAndFlush(job);
    }

    private ExpandJob seedExpandJob(String owner) {
        ExpandJob job = new ExpandJob(1L, "lastfm", JobStatus.FAILED, 3,
                Instant.now().plus(Duration.ofDays(28)));
        job.setOwner(owner);
        return expandJobRepository.saveAndFlush(job);
    }

    @Test
    void adminScanNowRejectsNonAdminAndLeavesTargetOwnersJobUntouched() throws Exception {
        String targetOwner = "admin-scan-target-a@example.com";
        ScanJob seeded = seedScanJob(targetOwner);
        // Re-fetch rather than use seeded.getNextDueAt() directly: Postgres timestamp columns are
        // microsecond precision, but a JVM Instant.now() can carry nanosecond precision (observed
        // on CI's Linux runners, not locally on macOS) -- comparing the pre-round-trip value
        // against a post-round-trip re-fetch would spuriously fail on precision alone, not because
        // anything actually changed. Both sides of the equality below must come from the DB.
        Instant originalNextDueAt = scanJobRepository.findById(seeded.getId()).orElseThrow().getNextDueAt();

        mockMvc.perform(post("/admin/scan-now")
                        .param("targetOwner", targetOwner)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", "not-admin@example.com"))))
                .andExpect(status().isForbidden());

        ScanJob after = scanJobRepository.findById(seeded.getId()).orElseThrow();
        assertThat(after.getNextDueAt()).isEqualTo(originalNextDueAt);
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getAttempts()).isEqualTo(3);
    }

    @Test
    void adminScanNowLetsAdminRedueADifferentOwnersJobs() throws Exception {
        String targetOwner = "admin-scan-target-b@example.com";
        ScanJob seeded = seedScanJob(targetOwner);

        mockMvc.perform(post("/admin/scan-now")
                        .param("targetOwner", targetOwner)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().is3xxRedirection());

        ScanJob after = scanJobRepository.findById(seeded.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getNextDueAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void adminScanNowHtmxNamesTheTargetOwnerInTheConfirmation() throws Exception {
        String targetOwner = "admin-scan-target-c@example.com";
        seedScanJob(targetOwner);

        String body = mockMvc.perform(post("/admin/scan-now")
                        .param("targetOwner", targetOwner)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("shows-region").contains(targetOwner);
        assertThat(body).doesNotContain("<head").doesNotContain("topbar");
    }

    @Test
    void adminExpandNowRejectsNonAdminAndLeavesTargetOwnersJobUntouched() throws Exception {
        String targetOwner = "admin-expand-target-a@example.com";
        ExpandJob seeded = seedExpandJob(targetOwner);
        // See the identical comment in adminScanNowRejectsNonAdminAndLeavesTargetOwnersJobUntouched:
        // must re-fetch to compare DB-precision-rounded values on both sides.
        Instant originalNextDueAt = expandJobRepository.findById(seeded.getId()).orElseThrow().getNextDueAt();

        mockMvc.perform(post("/artists/admin/expand-now")
                        .param("targetOwner", targetOwner)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", "not-admin@example.com"))))
                .andExpect(status().isForbidden());

        ExpandJob after = expandJobRepository.findById(seeded.getId()).orElseThrow();
        assertThat(after.getNextDueAt()).isEqualTo(originalNextDueAt);
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getAttempts()).isEqualTo(3);
    }

    @Test
    void adminExpandNowLetsAdminRedueADifferentOwnersJobs() throws Exception {
        String targetOwner = "admin-expand-target-b@example.com";
        ExpandJob seeded = seedExpandJob(targetOwner);

        mockMvc.perform(post("/artists/admin/expand-now")
                        .param("targetOwner", targetOwner)
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().is3xxRedirection());

        ExpandJob after = expandJobRepository.findById(seeded.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.SCHEDULED);
        assertThat(after.getAttempts()).isZero();
        assertThat(after.getNextDueAt()).isCloseTo(Instant.now(), within(1, ChronoUnit.MINUTES));
    }

    @Test
    void adminExpandNowFocusesTheSwappedInCopyOfItsOwnTriggerButton() throws Exception {
        seedExpandJob(OTHER_ALLOWED_EMAIL);

        String body = mockMvc.perform(post("/artists/admin/expand-now")
                        .param("targetOwner", OTHER_ALLOWED_EMAIL)
                        .header("HX-Request", "true")
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // #155: like the self-service expand-now, this trigger cannot hold its own focus across the
        // swap -- hx-disabled-elt="find button" disables (and so BLURS) it at request start, leaving
        // document.activeElement as <body>, which htmx's id-based restore deliberately skips. The
        // swapped-in copy of the button carries autofocus instead. This is the only class that
        // allow-lists a second user, so it is the only place the admin form actually renders.
        assertThat(countAutofocusElements(body)).isEqualTo(1);
        assertThat(body).containsPattern(
                "id=\"admin-expand-now\"[^>]*autofocus|autofocus[^>]*id=\"admin-expand-now\"");
        assertThat(body).contains("Expansion requested for " + OTHER_ALLOWED_EMAIL + ".");
    }

    @Test
    void showsPageOffersAdminDropdownOnlyToTheAdmin() throws Exception {
        String adminBody = mockMvc.perform(get("/")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(adminBody).contains("admin-scan-target").contains(OTHER_ALLOWED_EMAIL);

        String nonAdminBody = mockMvc.perform(get("/")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER_ALLOWED_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(nonAdminBody).doesNotContain("admin-scan-target");
    }

    @Test
    void candidatesPageOffersAdminDropdownOnlyToTheAdmin() throws Exception {
        String adminBody = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(adminBody).contains("admin-expand-target").contains(OTHER_ALLOWED_EMAIL);

        String nonAdminBody = mockMvc.perform(get("/artists/candidates")
                        .with(oidcLogin().idToken(t -> t.claim("email", OTHER_ALLOWED_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(nonAdminBody).doesNotContain("admin-expand-target");
    }
}
