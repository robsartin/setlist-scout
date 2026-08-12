package com.robsartin.setlistscout.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeoDistanceTest {

    @Test
    @DisplayName("distance between a point and itself is zero")
    void zeroForSamePoint() {
        assertThat(GeoDistance.milesBetween(30.2672, -97.7431, 30.2672, -97.7431)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.01));
    }

    @Test
    @DisplayName("Austin to San Antonio is about 74 miles")
    void austinToSanAntonio() {
        // Austin (30.2672, -97.7431) -> San Antonio (29.4241, -98.4936)
        double miles = GeoDistance.milesBetween(30.2672, -97.7431, 29.4241, -98.4936);
        assertThat(miles).isBetween(70.0, 80.0);
    }
}
