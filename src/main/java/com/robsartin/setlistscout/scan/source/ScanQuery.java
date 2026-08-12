package com.robsartin.setlistscout.scan.source;

import java.time.LocalDateTime;

/**
 * Everything a show source might need for one artist at the owner's location/window. The orchestrator
 * resolves {@code officialSiteUrl} (and caches a newly discovered one) before building this; sources
 * read only the fields they need and never write.
 */
public record ScanQuery(
        String artistName,
        String officialSiteUrl,
        String postalCode,
        Double latitude,
        Double longitude,
        int radiusMiles,
        String city,
        LocalDateTime windowStart,
        LocalDateTime windowEnd) {
}
