package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.CurrentUser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.time.Instant;
import java.util.List;

import static com.robsartin.setlistscout.support.CsvTestSupport.parseCsv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Issue #228: {@code GET /artists.csv}. A separate controller (not a method on {@link
 * ArtistController}) because that class carries a class-level {@code @RequestMapping("/artists")}
 * -- Spring MVC always combines a class-level mapping with the method's, with no way to opt a
 * single method out of it, so a method there could only ever answer {@code /artists/artists.csv},
 * not the top-level {@code /artists.csv} the issue brief asks for.
 */
class ArtistCsvControllerTest {

    private static final String OWNER = "rob@example.com";

    private ArtistRepository artistRepository;
    private ArtistCsvController controller;

    @BeforeEach
    void setUp() {
        artistRepository = mock(ArtistRepository.class);
        CurrentUser currentUser = mock(CurrentUser.class);
        when(currentUser.email()).thenReturn(OWNER);
        controller = new ArtistCsvController(artistRepository, currentUser);
    }

    private static Artist artist(String name, ArtistSource source, ArtistStatus status) {
        return new Artist(name, source, status, null, null);
    }

    @Test
    @DisplayName("issue #228: csv() returns text/csv with an attachment filename")
    void csvReturnsTextCsvWithAttachmentFilename() {
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of());

        ResponseEntity<byte[]> response = controller.csv();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"artists.csv\"");
    }

    @Test
    @DisplayName("issue #228: csv() is owner-scoped -- fetches only the signed-in user's own artists")
    void csvIsOwnerScoped() {
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of());

        controller.csv();

        verify(artistRepository).findByOwner(OWNER);
    }

    /**
     * The point of an export is to see everything (issue #228's explicit requirement) -- unlike
     * {@code ArtistController#list}, which shows only SEED/APPROVED, this must include every
     * status with {@code status} as its own column, proved here by exercising all five.
     */
    @Test
    @DisplayName("issue #228: csv() exports every artist status, not just the active (seed + approved) list")
    void csvExportsAllStatuses() {
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of(
                artist("Seed Band", ArtistSource.SEED_LIST, ArtistStatus.SEED),
                artist("Pending Band", ArtistSource.MEMBER_EXPANSION, ArtistStatus.PENDING_REVIEW),
                artist("Approved Band", ArtistSource.SIMILAR_EXPANSION, ArtistStatus.APPROVED),
                artist("Rejected Band", ArtistSource.TRIBUTE_EXPANSION, ArtistStatus.REJECTED),
                artist("Removed Band", ArtistSource.SEED_LIST, ArtistStatus.REMOVED)));

        ResponseEntity<byte[]> response = controller.csv();

        List<CSVRecord> records = parseCsv(response.getBody());
        assertThat(records).extracting(r -> r.get("name"), r -> r.get("status"))
                .containsExactlyInAnyOrder(
                        tuple("Seed Band", "SEED"),
                        tuple("Pending Band", "PENDING_REVIEW"),
                        tuple("Approved Band", "APPROVED"),
                        tuple("Rejected Band", "REJECTED"),
                        tuple("Removed Band", "REMOVED"));
    }

    @Test
    @DisplayName("issue #228: nullable fields (discoveredVia, officialSiteUrl) render as blank, not the string \"null\"")
    void nullableFieldsRenderBlank() {
        Artist a = artist("Wilco", ArtistSource.SEED_LIST, ArtistStatus.SEED);
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of(a));

        ResponseEntity<byte[]> response = controller.csv();

        List<CSVRecord> records = parseCsv(response.getBody());
        assertThat(records.get(0).get("discovered_via")).isEmpty();
        assertThat(records.get(0).get("official_site_url")).isEmpty();
        assertThat(records.get(0).get("created_at")).isEqualTo(a.getCreatedAt().toString());
    }

    @Test
    @DisplayName("issue #228: an artist name with a comma AND a double quote round-trips through the real endpoint")
    void csvFieldWithCommaAndQuoteRoundTrips() {
        String tricky = "The \"Legends\", Vol. 2";
        when(artistRepository.findByOwner(OWNER))
                .thenReturn(List.of(artist(tricky, ArtistSource.SEED_LIST, ArtistStatus.SEED)));

        ResponseEntity<byte[]> response = controller.csv();

        List<CSVRecord> records = parseCsv(response.getBody());
        assertThat(records.get(0).get("name")).isEqualTo(tricky);
    }

    @Test
    @DisplayName("issue #228: a non-ASCII artist name round-trips through the real endpoint")
    void csvNonAsciiNameRoundTrips() {
        String name = "Béla Fleck"; // one of production's 76 non-ASCII active-artist names
        when(artistRepository.findByOwner(OWNER))
                .thenReturn(List.of(artist(name, ArtistSource.SEED_LIST, ArtistStatus.SEED)));

        ResponseEntity<byte[]> response = controller.csv();

        List<CSVRecord> records = parseCsv(response.getBody());
        assertThat(records.get(0).get("name")).isEqualTo(name);
    }

    @Test
    @DisplayName("issue #228: discoveredVia and source pass through unchanged for an expansion-sourced artist")
    void discoveredViaAndSourcePassThrough() {
        Artist a = new Artist("Dawes", ArtistSource.SIMILAR_EXPANSION, ArtistStatus.PENDING_REVIEW,
                "Wilco", "note");
        when(artistRepository.findByOwner(OWNER)).thenReturn(List.of(a));

        ResponseEntity<byte[]> response = controller.csv();

        List<CSVRecord> records = parseCsv(response.getBody());
        assertThat(records.get(0).get("discovered_via")).isEqualTo("Wilco");
        assertThat(records.get(0).get("source")).isEqualTo("SIMILAR_EXPANSION");
    }
}
