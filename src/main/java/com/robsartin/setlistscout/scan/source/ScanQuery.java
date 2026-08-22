package com.robsartin.setlistscout.scan.source;

import java.time.LocalDateTime;

/**
 * Everything a show source might need for one artist at the owner's location/window. The orchestrator
 * resolves {@code officialSiteUrl} (and caches a newly discovered one) before building this; sources
 * read only the fields they need and never write. {@code state} is the owner's settings state
 * (derived from their ZIP geocode) -- band-site shows carry only a bare venue city string, so it's
 * used as the assumed state when geocoding that city for distance filtering (#28).
 * <p>
 * {@code defaultVenueName}/{@code defaultVenueCity} (#218) are the artist's configured fallback
 * venue -- read from {@code Artist} the same way {@code officialSiteUrl} is -- applied by {@link
 * BandSiteShowSource} only when a scraped show carries no venue of its own. Both null for every
 * artist that hasn't set one, and unused by every source but {@code band-site}.
 */
public record ScanQuery(
        String artistName,
        String officialSiteUrl,
        String defaultVenueName,
        String defaultVenueCity,
        String postalCode,
        Double latitude,
        Double longitude,
        int radiusMiles,
        String city,
        String state,
        LocalDateTime windowStart,
        LocalDateTime windowEnd) {

    /**
     * Convenience constructor for the common case of no configured venue default -- lets every
     * show-source call site that doesn't care about #218 (Bandsintown, Ticketmaster, and most
     * band-site tests) keep constructing a {@code ScanQuery} without threading two always-null
     * fields through.
     */
    public ScanQuery(String artistName, String officialSiteUrl, String postalCode, Double latitude,
            Double longitude, int radiusMiles, String city, String state,
            LocalDateTime windowStart, LocalDateTime windowEnd) {
        this(artistName, officialSiteUrl, null, null, postalCode, latitude, longitude, radiusMiles,
                city, state, windowStart, windowEnd);
    }
}
