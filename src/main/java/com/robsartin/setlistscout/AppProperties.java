package com.robsartin.setlistscout;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

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
            List<String> allowedEmails, // only these emails may log in -- see SecurityConfig
            String seedOwner            // the user who owns migrated/seed data and gets the seed-bands.txt list
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
            String postalCode,
            String city,
            String state,
            int radiusMiles,
            int monthsAhead
    ) {}
}
