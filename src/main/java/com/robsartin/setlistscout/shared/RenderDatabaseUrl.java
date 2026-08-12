package com.robsartin.setlistscout.shared;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses a Render/libpq-style Postgres connection URL
 * ("postgres[ql]://user:password@host[:port]/db[?query]") into the pieces Spring's datasource
 * needs: a JDBC URL plus a separate username and password.
 *
 * <p>Render exposes a managed database's connection string as an env var in libpq form and keeps
 * it current across credential rotations. Spring's {@code spring.datasource.url} wants JDBC form
 * with the credentials supplied separately, so this bridges the two. Render-generated passwords
 * are alphanumeric; the username/password are still URL-decoded so a percent-encoded value would
 * round-trip correctly.
 */
public final class RenderDatabaseUrl {

    // user has no ':' '@' '/'; password is everything up to the LAST '@' (host group forbids '@');
    // host has no '@' ':' '/' '?'; optional :port; '/'db (no '?'); optional '?'query.
    private static final Pattern PATTERN = Pattern.compile(
            "^postgres(?:ql)?://([^:@/]+):(.*)@([^@:/?]+)(?::(\\d+))?/([^?]+)(?:\\?(.*))?$");

    private RenderDatabaseUrl() {
    }

    public record Parsed(String jdbcUrl, String username, String password) {
    }

    public static Optional<Parsed> parse(String url) {
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        Matcher m = PATTERN.matcher(url.trim());
        if (!m.matches()) {
            return Optional.empty();
        }
        String username = decode(m.group(1));
        String password = decode(m.group(2));
        String host = m.group(3);
        String port = m.group(4);
        String database = m.group(5);
        String query = m.group(6);

        StringBuilder jdbc = new StringBuilder("jdbc:postgresql://").append(host);
        if (port != null) {
            jdbc.append(':').append(port);
        }
        jdbc.append('/').append(database);
        if (query != null && !query.isBlank()) {
            jdbc.append('?').append(query);
        }
        return Optional.of(new Parsed(jdbc.toString(), username, password));
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
