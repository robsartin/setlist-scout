package com.robsartin.setlistscout.scan.source;

import java.time.LocalDateTime;

/**
 * Everything a show source might need for one artist at the owner's location/window. The orchestrator
 * resolves {@code officialSiteUrl} (and caches a newly discovered one) before building this; sources
 * read only the fields they need and never write. {@code state} is the owner's settings state
 * (derived from their ZIP geocode) -- band-site shows carry only a bare venue city string, so it's
 * used as the assumed state when geocoding that city for distance filtering (#28).
 */
public record ScanQuery(
        String artistName,
        String officialSiteUrl,
        String postalCode,
        Double latitude,
        Double longitude,
        int radiusMiles,
        String city,
        String state,
        LocalDateTime windowStart,
        LocalDateTime windowEnd) {
}
