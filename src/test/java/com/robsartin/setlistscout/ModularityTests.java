package com.robsartin.setlistscout;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class ModularityTests {
    @Test
    void verifiesModularStructure() {
        ApplicationModules.of(SetlistScoutApplication.class).verify();
    }
}
