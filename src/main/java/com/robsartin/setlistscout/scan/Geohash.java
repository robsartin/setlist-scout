package com.robsartin.setlistscout.scan;

/**
 * Geohash encoding -- interleaves a lat/long pair's bits into a single base32 string, longer
 * strings meaning finer precision. Used to send Ticketmaster a {@code geoPoint} instead of a
 * ZIP-derived {@code postalCode}: Ticketmaster's postal-code index only covers ZIPs where it has
 * market presence and silently matches nothing for the ones it doesn't, while {@code geoPoint}
 * works directly from the coordinates already geocoded per ADR-0018 (see #152).
 */
public final class Geohash {

    private static final String BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz";

    private Geohash() {
    }

    /**
     * Encodes a lat/long pair as a base32 geohash {@code precision} characters long. Precision 9
     * (about 5m of resolution) is what was verified working against the live Ticketmaster API
     * for #152.
     */
    public static String encode(double latitude, double longitude, int precision) {
        double latMin = -90.0;
        double latMax = 90.0;
        double lonMin = -180.0;
        double lonMax = 180.0;

        StringBuilder geohash = new StringBuilder(precision);
        boolean evenBit = true; // bits interleave starting with longitude
        int bit = 0;
        int charBits = 0;

        while (geohash.length() < precision) {
            if (evenBit) {
                double mid = (lonMin + lonMax) / 2;
                if (longitude > mid) {
                    charBits |= (16 >> bit);
                    lonMin = mid;
                } else {
                    lonMax = mid;
                }
            } else {
                double mid = (latMin + latMax) / 2;
                if (latitude > mid) {
                    charBits |= (16 >> bit);
                    latMin = mid;
                } else {
                    latMax = mid;
                }
            }
            evenBit = !evenBit;

            if (bit < 4) {
                bit++;
            } else {
                geohash.append(BASE32.charAt(charBits));
                bit = 0;
                charBits = 0;
            }
        }
        return geohash.toString();
    }
}
