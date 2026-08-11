package com.robsartin.setlistscout.service;

import com.robsartin.setlistscout.config.AppProperties;

/** Shared fixture: a fully-populated AppProperties for service unit tests. */
final class TestAppProperties {

    private TestAppProperties() {}

    static AppProperties withKeys() {
        return new AppProperties(
                new AppProperties.Auth("owner@example.com"),
                new AppProperties.Apis(
                        "tm-key", "bit-app-id", "TestAgent/1.0 ( test@example.com )",
                        "discogs-token", "lastfm-key", "anthropic-key"),
                new AppProperties.Defaults("78701", "Austin", "TX", 50, 6));
    }
}
