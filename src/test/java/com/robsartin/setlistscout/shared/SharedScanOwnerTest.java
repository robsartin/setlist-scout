package com.robsartin.setlistscout.shared;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SharedScanOwnerTest {

    @Test
    @DisplayName("a generated key is recognised as a shared-scan key")
    void generatedKeyIsRecognised() {
        String key = SharedScanOwner.newKey();
        assertThat(SharedScanOwner.isSharedScanKey(key)).isTrue();
    }

    @Test
    @DisplayName("generated keys are unique")
    void generatedKeysAreUnique() {
        assertThat(SharedScanOwner.newKey()).isNotEqualTo(SharedScanOwner.newKey());
    }

    @Test
    @DisplayName("a real email is never a shared-scan key")
    void emailIsNotASharedScanKey() {
        assertThat(SharedScanOwner.isSharedScanKey("rob.sartin@gmail.com")).isFalse();
        assertThat(SharedScanOwner.isSharedScanKey("davidbuley01@gmail.com")).isFalse();
    }

    @Test
    @DisplayName("null and blank are not shared-scan keys, and do not throw")
    void nullAndBlankAreSafe() {
        assertThat(SharedScanOwner.isSharedScanKey(null)).isFalse();
        assertThat(SharedScanOwner.isSharedScanKey("")).isFalse();
        assertThat(SharedScanOwner.isSharedScanKey("   ")).isFalse();
    }

    @Test
    @DisplayName("an address that merely contains the prefix is not a shared-scan key")
    void prefixMustBeAtTheStart() {
        assertThat(SharedScanOwner.isSharedScanKey("not-shared:1234")).isFalse();
        assertThat(SharedScanOwner.isSharedScanKey("x@shared:example.com")).isFalse();
    }
}
