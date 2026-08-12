package com.robsartin.setlistscout.shared.observability;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CorrelationIdsTest {

    @Test
    void newIdIsAValidVersion7Uuid() {
        String id = CorrelationIds.newId();
        assertThat(CorrelationIds.isValid(id)).isTrue();
        assertThat(UUID.fromString(id).version()).isEqualTo(7);
    }

    @Test
    void successiveIdsAreTimeOrdered() {
        String first = CorrelationIds.newId();
        String second = CorrelationIds.newId();
        // v7 is time-ordered and lexicographically sortable; the generator is monotonic.
        assertThat(first.compareTo(second)).isLessThan(0);
    }

    @Test
    void isValidRejectsJunk() {
        assertThat(CorrelationIds.isValid(null)).isFalse();
        assertThat(CorrelationIds.isValid("")).isFalse();
        assertThat(CorrelationIds.isValid("not-a-uuid")).isFalse();
    }
}
