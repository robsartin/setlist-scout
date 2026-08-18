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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Drives the admin-only queues page (#201) against a booted context + Postgres, signed in as
 * test users -- mirrors {@code AdminCrossAccountActionsTest}'s shape for the existing admin
 * escape hatches. The security-rejection test is the point of this class: a non-admin must get a
 * 403 from the endpoint itself, not merely a hidden nav link. The rendering tests prove failed
 * work (including a shared-scan owner's) actually surfaces with its error text, and that the nav
 * link is gated on {@code isAdmin} rather than always present.
 * <p>
 * Deliberately does NOT assert exact aggregate counts here: this app's full Spring context runs
 * {@code catalog.CatalogSeeder} at startup, which seeds real scan/expand jobs for the default
 * admin owner (see CLAUDE.md's "CatalogSeeder's 143 boot-stamped jobs" note) -- a global count
 * assertion in this class would be coupled to that unrelated seed data. Aggregate-count
 * correctness (including a shared-scan owner) is proven in isolation, against a cleared table,
 * in scan.ScanJobRepositoryTest / expansion.ExpandJobRepositoryTest / catalog.ArtistImportRepositoryTest.
 * This class instead asserts on distinctive seeded values that cannot collide with boot-seeded data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class AdminQueuePageTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    // The default from application.yml (setlistscout.auth.admin-email) -- nothing in this test
    // suite overrides it, so this is genuinely "the admin" for every Testcontainers test that
    // boots a real context (same constant AdminCrossAccountActionsTest uses).
    private static final String ADMIN_EMAIL = "rob.sartin@gmail.com";
    private static final String NON_ADMIN_EMAIL = "not-admin@example.com";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ScanJobRepository scanJobRepository;

    @Autowired
    private ExpandJobRepository expandJobRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private ScanJob seedFailedScanJob(String owner, Long artistId, String source, int attempts, String lastError) {
        ScanJob job = new ScanJob(artistId, source, JobStatus.FAILED, attempts,
                Instant.now().plus(14, ChronoUnit.DAYS));
        job.setOwner(owner);
        job.setLastError(lastError);
        return scanJobRepository.saveAndFlush(job);
    }

    private ExpandJob seedFailedExpandJob(String owner, Long artistId, String source, int attempts, String lastError) {
        ExpandJob job = new ExpandJob(artistId, source, JobStatus.FAILED, attempts,
                Instant.now().plus(28, ChronoUnit.DAYS));
        job.setOwner(owner);
        job.setLastError(lastError);
        return expandJobRepository.saveAndFlush(job);
    }

    /**
     * ArtistImport's constructor is package-private to catalog, and its repository's
     * insertIfAbsent is a native @Modifying query that needs an ambient transaction this
     * MockMvc-driven test method doesn't carry -- so this seeds the row directly via SQL,
     * already FAILED, instead of the insert-then-flip-status dance
     * catalog.ArtistImportRepositoryTest uses inside its own @Transactional test methods.
     * <p>
     * Binds the timestamp columns as {@code OffsetDateTime}, not the raw {@code Instant} every
     * other class in this codebase uses: Hibernate maps {@code Instant} natively, but the plain
     * pgjdbc driver underneath {@code JdbcTemplate}'s varargs {@code update} cannot infer a SQL
     * type for it on its own ({@code PSQLException: Can't infer the SQL type to use for an
     * instance of java.time.Instant}) -- confirmed by actually running this test, not assumed.
     */
    private void seedFailedImport(String owner, String name, int attempts, String lastError) {
        OffsetDateTime now = OffsetDateTime.ofInstant(Instant.now(), ZoneOffset.UTC);
        jdbcTemplate.update("""
                INSERT INTO artist_import
                    (owner, name, normalized_name, status, attempts, last_error, next_due_at, created_at)
                VALUES (?, ?, ?, 'FAILED', ?, ?, ?, ?)
                """,
                owner, name, name.toLowerCase(java.util.Locale.ROOT), attempts, lastError, now, now);
    }

    @Test
    void nonAdminGetsForbidden() throws Exception {
        mvc.perform(get("/admin/queues").with(oidcLogin().idToken(t -> t.claim("email", NON_ADMIN_EMAIL))))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminGetsThePageWithFailedWorkFromAllThreeQueuesAndTheirError() throws Exception {
        String sharedOwner = "shared:" + java.util.UUID.randomUUID();
        seedFailedScanJob("admin-page-scan-owner@example.com", 501L, "ticketmaster", 5,
                "Ticketmaster API returned 500 Internal Server Error");
        seedFailedExpandJob(sharedOwner, 502L, "musicbrainz-relation", 2,
                "MusicBrainz lookup timed out after 10000ms");
        seedFailedImport("admin-page-import-owner@example.com", "Unseedable Band Name", 3,
                "could not resolve any matching artist after 3 attempts");

        String body = mvc.perform(get("/admin/queues")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // Failed work, with its error -- the highest-priority part of the page (#201 item 1).
        assertThat(body)
                .contains("admin-page-scan-owner@example.com")
                .contains("Ticketmaster API returned 500 Internal Server Error")
                // Shared-scan owners are real work too, not a synthetic id nobody looks at.
                .contains(sharedOwner)
                .contains("MusicBrainz lookup timed out after 10000ms")
                .contains("admin-page-import-owner@example.com")
                .contains("Unseedable Band Name")
                .contains("could not resolve any matching artist after 3 attempts");

        // Accessibility: semantic table with scope="col" headers, wide tables in .table-scroll.
        assertThat(body).contains("scope=\"col\"").contains("table-scroll");

        // Full page (not an htmx fragment) -- carries the shared layout.
        assertThat(body).contains("/css/app.css");
    }

    @Test
    void navLinkIsPresentOnlyForTheAdmin() throws Exception {
        String adminBody = mvc.perform(get("/")
                        .with(oidcLogin().idToken(t -> t.claim("email", ADMIN_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(adminBody).contains("/admin/queues");

        String nonAdminBody = mvc.perform(get("/")
                        .with(oidcLogin().idToken(t -> t.claim("email", NON_ADMIN_EMAIL))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(nonAdminBody).doesNotContain("/admin/queues");
    }
}
