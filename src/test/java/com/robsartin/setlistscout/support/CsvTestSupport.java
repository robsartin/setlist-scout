package com.robsartin.setlistscout.support;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Parses a CSV-download response body with a REAL parser (issue #228) -- shared by every CSV
 * endpoint's tests ({@code ShowControllerTest}, {@code SharedScanControllerTest}, {@code
 * ArtistCsvControllerTest}) so none of them are tempted to fall back to a string-match assertion.
 * The issue brief is explicit that a string-match assertion can pass on output no parser could
 * read, which is exactly the failure this feature must not have.
 */
public final class CsvTestSupport {

    private CsvTestSupport() {
    }

    /**
     * Strips a leading UTF-8 BOM (every response from {@code CsvResponses.download} carries one --
     * see its javadoc) the same way a well-behaved consumer would, then parses the rest for real.
     */
    public static List<CSVRecord> parseCsv(byte[] body) {
        String text = new String(body, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '﻿') {
            text = text.substring(1);
        }
        CSVFormat format = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = CSVParser.parse(new StringReader(text), format)) {
            return parser.getRecords();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
