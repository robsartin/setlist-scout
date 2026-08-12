package com.robsartin.setlistscout.scan;

/**
 * Great-circle (Haversine) distance in miles between two lat/long points. Used to filter
 * Bandsintown events -- which have venue coordinates but no server-side radius -- to the
 * saved search radius around the user's ZIP (see ADR-0018).
 */
public final class GeoDistance {

    private static final double EARTH_RADIUS_MILES = 3958.8;

    private GeoDistance() {
    }

    public static double milesBetween(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_MILES * c;
    }
}
