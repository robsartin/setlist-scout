package com.robsartin.setlistscout.shared;

import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static com.robsartin.setlistscout.support.CsvTestSupport.parseCsv;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Issue #228: the shared RFC 4180 writer every CSV-download endpoint funnels through
 * (ShowController#showsCsv, SharedScanController's per-pairing CSV, ArtistCsvController).
 * <p>
 * Every round-trip assertion here goes through a REAL CSV parser ({@link
 * com.robsartin.setlistscout.support.CsvTestSupport#parseCsv}, backed by Commons CSV's own {@link
 * CSVParser}), never a string-match on the raw bytes -- the issue brief is explicit that a
 * string-match assertion can pass on output no parser could read, which is exactly the failure
 * this feature must not have.
 */
class CsvResponsesTest {

    private static final List<String> HEADER = List.of("name", "note");

    @Test
    @DisplayName("download() sets a text/csv content type and an attachment filename")
    void downloadSetsCsvContentTypeAndAttachmentFilename() {
        ResponseEntity<byte[]> response = CsvResponses.download("test.csv", HEADER, List.of(List.of("a", "b")));

        assertThat(response.getHeaders().getContentType()).isNotNull();
        assertThat(response.getHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(response.getHeaders().getFirst("Content-Disposition"))
                .isEqualTo("attachment; filename=\"test.csv\"");
    }

    @Test
    @DisplayName("a field with a comma AND a double quote round-trips through a real CSV parser")
    void fieldWithCommaAndQuoteRoundTrips() {
        String tricky = "The \"Legends\", Vol. 2";
        ResponseEntity<byte[]> response = CsvResponses.download("test.csv", HEADER, List.of(List.of(tricky, "n")));

        List<CSVRecord> records = parseCsv(response.getBody());

        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("name")).isEqualTo(tricky);
    }

    @Test
    @DisplayName("an embedded quote alone round-trips (RFC 4180 doubled-quote escaping)")
    void embeddedQuoteAloneRoundTrips() {
        String value = "Say \"hello\"";
        ResponseEntity<byte[]> response = CsvResponses.download("test.csv", HEADER, List.of(List.of(value, "n")));

        List<CSVRecord> records = parseCsv(response.getBody());

        assertThat(records.get(0).get("name")).isEqualTo(value);
    }

    @Test
    @DisplayName("a non-ASCII name round-trips through a real CSV parser")
    void nonAsciiNameRoundTrips() {
        String name = "Antonio Sánchez"; // one of production's 76 non-ASCII active-artist names
        ResponseEntity<byte[]> response = CsvResponses.download("test.csv", HEADER, List.of(List.of(name, "n")));

        List<CSVRecord> records = parseCsv(response.getBody());

        assertThat(records.get(0).get("name")).isEqualTo(name);
    }

    @Test
    @DisplayName("issue #228: the body is prefixed with a UTF-8 BOM so Excel on Windows doesn't "
            + "misread the non-ASCII names this export contains")
    void bodyStartsWithUtf8Bom() {
        ResponseEntity<byte[]> response = CsvResponses.download("test.csv", HEADER, List.of());

        byte[] body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body[0]).isEqualTo((byte) 0xEF);
        assertThat(body[1]).isEqualTo((byte) 0xBB);
        assertThat(body[2]).isEqualTo((byte) 0xBF);
    }

    @Test
    @DisplayName("a CR/LF embedded in a field round-trips as a single quoted field, not a broken row")
    void embeddedNewlineRoundTrips() {
        String value = "line one\nline two";
        ResponseEntity<byte[]> response = CsvResponses.download("test.csv", HEADER, List.of(List.of(value, "n")));

        List<CSVRecord> records = parseCsv(response.getBody());

        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("name")).isEqualTo(value);
    }
}
