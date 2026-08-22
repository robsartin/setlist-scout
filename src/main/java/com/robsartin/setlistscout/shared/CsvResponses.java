package com.robsartin.setlistscout.shared;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * RFC 4180 CSV rendering shared by every CSV-download endpoint (issue #228): {@code
 * ShowController#showsCsv}, {@code SharedScanController}'s per-pairing CSV, and {@code
 * ArtistCsvController}. One place to get quoting/escaping right -- doubling an embedded quote,
 * quoting any field that contains a comma/quote/CR/LF -- rather than three hand-rolled
 * string-concatenation copies. Apache Commons CSV was added for exactly this (see
 * build.gradle.kts): a single small, dependency-free, widely used jar built around this one
 * problem, which is safer than hand-rolling it -- the issue brief is explicit that a string-match
 * test can pass on output no real parser could read, which is precisely the risk a hand-rolled
 * writer carries and a battle-tested one does not.
 */
public final class CsvResponses {

    /**
     * A UTF-8 byte-order mark, written before the CSV body -- never as a column or row of its own.
     * Opened by double-click (not through an explicit "import as UTF-8" step), Excel on Windows
     * guesses the system codepage for a BOM-less file and corrupts every non-ASCII byte; this
     * export has real non-ASCII names in production (76 active artists -- "Antonio Sánchez",
     * "Øystein Sevåg", "Béla Fleck", "アコースフィア"), so that corruption is the realistic failure
     * mode here, not a hypothetical one. A leading BOM is harmless to every other consumer this
     * export is for -- Excel on macOS, Google Sheets, Numbers, Python's csv module, and Commons
     * CSV's own parser (this project's own tests included) all either expect it or ignore it --
     * so writing it unconditionally is the one choice that is safe everywhere, rather than
     * guessing which OS or app will open the file.
     */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static final MediaType TEXT_CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private CsvResponses() {
    }

    /**
     * One CSV attachment response: an RFC 4180 body (BOM-prefixed UTF-8, {@code \r\n} record
     * separators) plus {@code Content-Type: text/csv} and a {@code Content-Disposition: attachment}
     * filename. {@code filename} is always a literal this codebase controls (e.g. {@code
     * "shows.csv"}), never user input, so it needs no escaping of its own.
     */
    public static ResponseEntity<byte[]> download(String filename, List<String> header, List<List<String>> rows) {
        // .get(), not the deprecated .build() -- Builder implements Supplier<CSVFormat> and 1.14.x
        // deprecated build() in its favor.
        CSVFormat format = CSVFormat.RFC4180.builder().setHeader(header.toArray(new String[0])).get();
        StringWriter writer = new StringWriter();
        try (CSVPrinter printer = new CSVPrinter(writer, format)) {
            for (List<String> row : rows) {
                printer.printRecord(row);
            }
        } catch (IOException e) {
            // StringWriter never actually throws IOException -- keeps this method's signature
            // checked-exception-free for its Controller callers.
            throw new UncheckedIOException(e);
        }
        byte[] body = writer.toString().getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, withBom, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, withBom, UTF8_BOM.length, body.length);

        return ResponseEntity.ok()
                .contentType(TEXT_CSV_UTF8)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(withBom);
    }
}
