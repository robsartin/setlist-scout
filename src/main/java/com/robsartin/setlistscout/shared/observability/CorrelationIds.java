package com.robsartin.setlistscout.shared.observability;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;

import java.util.UUID;

/** Time-ordered (UUIDv7) correlation ids. v7 sorts chronologically, which keeps logs orderable. */
public final class CorrelationIds {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private CorrelationIds() {
    }

    public static String newId() {
        return GENERATOR.generate().toString();
    }

    public static boolean isValid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
