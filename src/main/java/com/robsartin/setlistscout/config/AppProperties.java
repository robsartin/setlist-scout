package com.robsartin.setlistscout.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * API keys and startup defaults, sourced from environment variables (see application.yml).
 * Location/radius is NOT here on purpose -- that lives in the database (SearchSettings)
 * so it can be changed from the web page without a redeploy.
 */
@ConfigurationProperties(prefix = "setlistscout")
public record AppProperties(
        Auth auth,
        Apis apis,
        Defaults defaults
) {
    public record Auth(
            String allowedEmail // only this email may log in -- see SecurityConfig
    ) {}

    public record Apis(
            String ticketmasterApiKey,
            String bandsintownAppId,
            String musicBrainzUserAgent, // MusicBrainz requires a descriptive User-Agent, not a key
            String discogsToken,
            String lastFmApiKey,
            String anthropicApiKey // for the LLM-based similar-artist cross-check and tribute-band lookup
    ) {}

    public record Defaults(
            String city,
            String state,
            int radiusMiles,
            int monthsAhead
    ) {}
}
