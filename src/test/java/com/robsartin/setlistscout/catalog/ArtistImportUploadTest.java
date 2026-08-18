package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Real-path Testcontainers proof of issue #177's redesigned {@code POST /artists/upload}: the
 * request QUEUES names into {@code artist_import} and returns immediately, instead of the old
 * behavior of calling {@code ArtistSeedService#addSeedIfNew} synchronously per line inside the
 * HTTP request -- the thing that 502'd on a real 1,138-name file, killed part-way by Render's
 * free-tier idle spin-down having imported only 79 names.
 * <p>
 * The import poller is disabled for the whole class (not just the "returns before processing"
 * test) so every pending-count assertion here is deterministic instead of racing the background
 * drain -- {@link ArtistImportPoller} draining a row mid-assertion would make counts flaky
 * regardless of which specific behavior a given test method is proving. Each test uses its own
 * unique owner (no per-test rollback -- see {@link AbstractPostgresIntegrationTest}'s class doc),
 * matching {@code ArtistPageRenderTest}'s convention.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class ArtistImportUploadTest extends AbstractPostgresIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void disableImportPoller(DynamicPropertyRegistry registry) {
        registry.add("setlistscout.import-poller-enabled", () -> false);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ArtistImportRepository artistImportRepository;

    @Autowired
    private ArtistRepository artistRepository;

    private static MockMultipartFile fileOf(String contents) {
        return new MockMultipartFile("file", "artists.txt", "text/plain", contents.getBytes(StandardCharsets.UTF_8));
    }

    private MvcResult upload(String owner, String contents) throws Exception {
        return mockMvc.perform(multipart("/artists/upload").file(fileOf(contents))
                        .with(csrf())
                        .with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().is3xxRedirection())
                .andReturn();
    }

    private static String uploadMessage(MvcResult result) {
        return (String) result.getFlashMap().get("uploadMessage");
    }

    @Test
    @DisplayName("issue #177: the request queues and returns BEFORE any seeding happens -- pending "
            + "count matches the file, and no artist has been created yet")
    void returnsBeforeProcessing() throws Exception {
        String owner = "upload-returns-before-processing@example.com";

        upload(owner, "Wilco\nDawes\nThe National\n");

        assertThat(artistImportRepository.countByOwnerAndStatus(owner, ArtistImportStatus.PENDING)).isEqualTo(3);
        assertThat(artistRepository.findByOwnerAndStatusIn(owner, List.of(ArtistStatus.values())))
                .as("no artist row exists for this owner yet -- the disabled poller never ran, so "
                        + "the endpoint itself must not have seeded anything synchronously")
                .isEmpty();
    }

    @Test
    @DisplayName("duplicates within one file, differing only by case, are queued once")
    void duplicatesWithinFileQueuedOnce() throws Exception {
        String owner = "upload-dedupe@example.com";

        upload(owner, "Wilco\nwilco\n");

        assertThat(artistImportRepository.countByOwnerAndStatus(owner, ArtistImportStatus.PENDING)).isEqualTo(1);
        assertThat(artistImportRepository.findByOwnerAndStatusOrderByNameAsc(owner, ArtistImportStatus.PENDING))
                .extracting(ArtistImport::getName).containsExactly("Wilco");
    }

    @Test
    @DisplayName("blank lines and # comments are skipped, not queued")
    void blankLinesAndCommentsSkipped() throws Exception {
        String owner = "upload-blanks-comments@example.com";

        upload(owner, "Wilco\n\n   \n# a comment\nDawes\n");

        assertThat(artistImportRepository.countByOwnerAndStatus(owner, ArtistImportStatus.PENDING)).isEqualTo(2);
        assertThat(artistImportRepository.findByOwnerAndStatusOrderByNameAsc(owner, ArtistImportStatus.PENDING))
                .extracting(ArtistImport::getName).containsExactlyInAnyOrder("Wilco", "Dawes");
    }

    @Test
    @DisplayName("re-uploading the same file while rows are still PENDING queues nothing new")
    void reUploadWhilePendingQueuesNothingNew() throws Exception {
        String owner = "upload-reupload-pending@example.com";
        String contents = "Wilco\nDawes\n";

        upload(owner, contents);
        assertThat(artistImportRepository.countByOwnerAndStatus(owner, ArtistImportStatus.PENDING)).isEqualTo(2);

        upload(owner, contents);
        assertThat(artistImportRepository.countByOwnerAndStatus(owner, ArtistImportStatus.PENDING))
                .as("still just the first upload's rows -- the partial unique index makes the "
                        + "second upload's inserts a no-op")
                .isEqualTo(2);
    }

    @Test
    @DisplayName("the MAX_UPLOAD_LINES cap (2000) still applies")
    void maxUploadLinesCapApplies() throws Exception {
        String owner = "upload-cap@example.com";
        String contents = IntStream.rangeClosed(1, ArtistImportService.MAX_UPLOAD_LINES + 5)
                .mapToObj(i -> "Cap Artist " + i)
                .collect(Collectors.joining("\n"));

        upload(owner, contents);

        assertThat(artistImportRepository.countByOwnerAndStatus(owner, ArtistImportStatus.PENDING))
                .isEqualTo(ArtistImportService.MAX_UPLOAD_LINES);
    }

    @Test
    @DisplayName("owner scoping: queued rows belong to the uploading user, not another owner")
    void queuedRowsAreOwnerScoped() throws Exception {
        String owner = "upload-owner-a@example.com";
        String other = "upload-owner-b@example.com";

        upload(owner, "Wilco\nDawes\n");
        upload(other, "The National\n");

        assertThat(artistImportRepository.countByOwnerAndStatus(owner, ArtistImportStatus.PENDING)).isEqualTo(2);
        assertThat(artistImportRepository.countByOwnerAndStatus(other, ArtistImportStatus.PENDING)).isEqualTo(1);
        assertThat(artistImportRepository.findByOwnerAndStatusOrderByNameAsc(owner, ArtistImportStatus.PENDING))
                .extracting(ArtistImport::getName).containsExactlyInAnyOrder("Wilco", "Dawes");
        assertThat(artistImportRepository.findByOwnerAndStatusOrderByNameAsc(other, ArtistImportStatus.PENDING))
                .extracting(ArtistImport::getName).containsExactly("The National");
    }

    @Test
    @DisplayName("the flash message reports the queued count, not an \"added\" count")
    void flashMessageReportsQueuedCount() throws Exception {
        String owner = "upload-flash-message@example.com";

        MvcResult result = upload(owner, "Wilco\nDawes\nThe National\n");

        assertThat(uploadMessage(result)).contains("Queued").contains("3").doesNotContain("Added");
    }

    @Test
    @DisplayName("issue #177: the Artists page shows a plain-text \"Importing: N remaining.\" "
            + "notice, with role=\"status\", once names are queued")
    void progressDisplayShowsPendingCountAfterUpload() throws Exception {
        String owner = "upload-progress-pending@example.com";
        upload(owner, "Wilco\nDawes\nThe National\n");

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        // The exact tag, not just the text anywhere on the page -- proves role="status" sits on
        // the SAME element as the message, matching how uploadMessage above it is announced.
        assertThat(body).contains("<p role=\"status\" class=\"upload-message\">Importing: 3 remaining.</p>");
    }

    @Test
    @Transactional
    @DisplayName("issue #177: the Artists page lists FAILED import names, from "
            + "findByOwnerAndStatusOrderByNameAsc -- proving the model wiring, not the poller's own "
            + "retry logic (already covered by ArtistImportPollerTest)")
    void progressDisplayListsFailedNames() throws Exception {
        String owner = "upload-progress-failed@example.com";
        Instant now = Instant.now();
        artistImportRepository.insertIfAbsent(owner, "Broken Name", ArtistNameNormalizer.normalize("Broken Name"),
                now, now);
        ArtistImport row = artistImportRepository
                .findByOwnerAndStatusOrderByNameAsc(owner, ArtistImportStatus.PENDING).get(0);
        row.setStatus(ArtistImportStatus.FAILED);
        row.setLastError("boom");
        artistImportRepository.save(row);

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("1 name could not be imported.");
        assertThat(body).contains("Broken Name");
    }

    @Test
    @DisplayName("issue #175: the upload form and its \"Importing: N remaining.\" notice both "
            + "render ABOVE the active list -- a status message stranded away from the form it "
            + "describes would be worse than not moving either")
    void uploadFormAndProgressNoticeRenderAboveActiveListTogether() throws Exception {
        String owner = "upload-forms-above-list@example.com";
        upload(owner, "Wilco\n");

        String body = mockMvc.perform(get("/artists").with(oidcLogin().idToken(t -> t.claim("email", owner))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        int progressIndex = body.indexOf("Importing: 1 remaining.");
        int uploadFormIndex = body.indexOf("action=\"/artists/upload\"");
        int activeSectionIndex = body.indexOf("id=\"active-section\"");

        assertThat(progressIndex).as("progress notice must render").isPositive();
        assertThat(uploadFormIndex).as("upload form must render").isPositive();
        assertThat(activeSectionIndex).as("active list must render").isPositive();
        assertThat(progressIndex).as("import progress notice renders above the active list")
                .isLessThan(activeSectionIndex);
        assertThat(uploadFormIndex).as("upload form renders above the active list")
                .isLessThan(activeSectionIndex);
    }
}
