package com.robsartin.setlistscout.scan;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeohashTest {

    @Test
    @DisplayName("Meredith NH at precision 9 matches the value verified against the live Ticketmaster API (#152)")
    void meredithNhAtPrecisionNine() {
        // 43.6311,-71.4997 -> drv0hyz98 confirmed against the real Ticketmaster geoPoint
        // parameter while diagnosing #152 (David's ZIP, 03253, that Ticketmaster's postalCode
        // index doesn't resolve).
        assertThat(Geohash.encode(43.6311, -71.4997, 9)).isEqualTo("drv0hyz98");
    }

    @Test
    @DisplayName("the Wikipedia Geohash article's reference example at precision 5")
    void wikipediaReferenceExample() {
        // https://en.wikipedia.org/wiki/Geohash -- "ezs42" is the canonical worked example for
        // (42.6, -5.6), independent of anything verified for #152. Cross-checking against a
        // second, unrelated known-good pair guards against an implementation that merely
        // reproduces the one value it was written to match.
        assertThat(Geohash.encode(42.6, -5.6, 5)).isEqualTo("ezs42");
    }

    @Test
    @DisplayName("precision controls the output length")
    void precisionControlsLength() {
        assertThat(Geohash.encode(43.6311, -71.4997, 5)).hasSize(5);
        assertThat(Geohash.encode(43.6311, -71.4997, 6)).hasSize(6);
        assertThat(Geohash.encode(43.6311, -71.4997, 9)).hasSize(9);
    }
}
