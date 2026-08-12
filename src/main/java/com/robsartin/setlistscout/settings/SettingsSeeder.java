package com.robsartin.setlistscout.settings;

import com.robsartin.setlistscout.AppProperties;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

/**
 * Runs once at startup. Safe to run every deploy -- creates the seed owner's settings row
 * (default ZIP, geocoded) only if they don't have one yet (SearchSettings is a pinned singleton row).
 */
@Component
public class SettingsSeeder implements CommandLineRunner {

    private final SettingsService settingsService;
    private final AppProperties appProperties;

    public SettingsSeeder(SettingsService settingsService, AppProperties appProperties) {
        this.settingsService = settingsService;
        this.appProperties = appProperties;
    }

    @Override
    public void run(String... args) throws Exception {
        settingsService.getOrCreateSettings(appProperties.auth().seedOwner());
    }
}
