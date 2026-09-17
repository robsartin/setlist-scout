package com.robsartin.setlistscout.shared;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Wikidata queries for the film half of Setlist Scout (#286), over the public SPARQL endpoint.
 *
 * <h2>Why SPARQL and not the entity API</h2>
 * Wikidata asserts a film's credits on the <em>film</em>: {@code P57} director, {@code P161} cast
 * member, {@code P58} screenwriter, {@code P162} producer all point film -> person. Fetching a
 * person's entity therefore returns no filmography at all; a filmography is a reverse lookup, and
 * the query service is the only public way to run one.
 *
 * <h2>Query-only</h2>
 * Like {@code expansion.source.RelationSource}, this never writes and never creates catalog rows.
 * It answers two questions and leaves every decision to its caller.
 */
@Service
public class WikidataService {

    private static final Logger log = LoggerFactory.getLogger(WikidataService.class);

    /** A Wikidata item id. Checked before interpolation -- see {@link #filmography}. */
    private static final Pattern QID = Pattern.compile("Q[0-9]+");

    private static final String ENTITY_PREFIX = "http://www.wikidata.org/entity/";

    /**
     * Enough rows to see that a name is ambiguous, not enough to pay for a popular one. The caller
     * refuses anything past the first distinct match, so the exact ceiling only has to exceed 1.
     */
    private static final int RESOLVE_LIMIT = 20;

    /** A bound on one person's credits: Scorsese returns 410 rows for 112 films. */
    private static final int FILMOGRAPHY_LIMIT = 5000;

    private final RestClient restClient;

    @Autowired
    public WikidataService(
            @Value("${setlistscout.wikidata.endpoint:https://query.wikidata.org/sparql}") String endpoint,
            @Value("${setlistscout.wikidata.user-agent:${setlistscout.apis.music-brainz-user-agent}}")
            String userAgent) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(10));
        // The query service gives a query 60s before it times out server-side; wait slightly longer
        // so a slow-but-succeeding query is not cut off by this client instead.
        factory.setReadTimeout(Duration.ofSeconds(70));
        this.restClient = RestClient.builder()
                .baseUrl(endpoint)
                // WDQS asks every client to identify itself and give a contact; an anonymous agent
                // is the first thing throttled. Shares MusicBrainz's descriptive value by default
                // rather than restating the contact address in a second place.
                .defaultHeader(HttpHeaders.USER_AGENT, userAgent)
                .defaultHeader(HttpHeaders.ACCEPT, "application/sparql-results+json")
                .requestFactory(factory)
                .build();
    }

    /**
     * The single Wikidata entity whose English label is exactly {@code name}, if there is exactly
     * one, restricted to people and musical groups.
     *
     * <h2>Exactly one, or nothing</h2>
     * This deliberately refuses rather than ranks. A wrong QID here is the one error no later step
     * can catch: it is well-formed, it resolves, and it attaches somebody else's entire filmography
     * to an artist the owner follows. Against live Wikidata "Willie Nelson" matches the musician
     * <em>and</em> an American boxer, and "Nirvana" matches three different bands -- so the honest
     * answer for those names is "unresolved", and the curated MusicBrainz link
     * ({@code MusicBrainzService#findWikidataQid}) is what resolves them instead.
     *
     * <h2>Musical groups, not only humans</h2>
     * The type guard admits {@code Q215380} (musical group) as well as {@code Q5} (human), because
     * bands carry film credits in their own right -- The Beatles have 5, Nirvana 4. A humans-only
     * guard would silently drop exactly the screenings this feature exists to surface.
     */
    public Optional<WikidataEntity> resolveByExactLabel(String name) {
        String query = """
                SELECT DISTINCT ?ent ?entLabel ?entDescription WHERE {
                  ?ent rdfs:label "%s"@en .
                  { ?ent wdt:P31 wd:Q5 } UNION { ?ent wdt:P31/wdt:P279* wd:Q215380 }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
                } LIMIT %d
                """.formatted(escapeLiteral(name), RESOLVE_LIMIT);

        List<Map<String, String>> rows = select(query, "entity resolution");

        Map<String, WikidataEntity> byQid = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String qid = localName(row.get("ent"));
            if (qid == null) continue;
            byQid.putIfAbsent(qid, new WikidataEntity(qid, row.get("entLabel"), row.get("entDescription")));
        }

        if (byQid.size() != 1) {
            log.atInfo().addKeyValue("source", "wikidata").addKeyValue("name", name)
                    .addKeyValue("matches", byQid.size())
                    .log(byQid.isEmpty() ? "name resolved to no entity" : "name is ambiguous, left unresolved");
            return Optional.empty();
        }
        return byQid.values().stream().findFirst();
    }

    /**
     * The English label and description of one known QID.
     *
     * <p>Exists so a resolved identity is legible. A QID that came from MusicBrainz's curated link
     * arrives as a bare number, and "Q206112" on an artist page tells nobody whether the right
     * Willie Nelson was matched. "American country musician (born 1933)" does.
     */
    public Optional<WikidataEntity> describe(String qid) {
        if (qid == null || !QID.matcher(qid).matches()) {
            log.atWarn().addKeyValue("source", "wikidata").addKeyValue("qid", qid)
                    .log("refusing a malformed QID");
            return Optional.empty();
        }

        String query = """
                SELECT ?entLabel ?entDescription WHERE {
                  BIND(wd:%s AS ?ent)
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
                }
                """.formatted(qid);

        return select(query, "entity description").stream()
                .findFirst()
                .map(row -> new WikidataEntity(qid, row.get("entLabel"), row.get("entDescription")));
    }

    /**
     * Every film this entity is credited on, one credit per (film, role) pair.
     *
     * <h2>The earliest release year, and why the choice matters</h2>
     * {@code P577} (publication date) is multi-valued -- one statement per country release -- so 38
     * of Q41148's 112 films carry more than one year and 4 carry none. Taken as given, a third of
     * films would have two identities. This collapses each film to {@code MIN(year)}, its original
     * release, and leaves the year null when Wikidata states none rather than dropping the film.
     *
     * <p>Collapsed here rather than in the query: aggregating across {@code wikibase:label}'s
     * generated variables needs every one of them in the GROUP BY, which makes the query harder to
     * read than the loop that replaces it and no cheaper to run.
     */
    public List<WikidataFilmCredit> filmography(String qid) {
        if (qid == null || !QID.matcher(qid).matches()) {
            // Interpolating an unchecked value into wd:<qid> would be a SPARQL injection, and a
            // caller holding a malformed QID has a bug worth seeing rather than a query to run.
            log.atWarn().addKeyValue("source", "wikidata").addKeyValue("qid", qid)
                    .log("refusing a malformed QID");
            return List.of();
        }

        String query = """
                SELECT ?film ?filmLabel ?year ?role WHERE {
                  VALUES (?p ?role) {
                    (wdt:P57 "DIRECTED")
                    (wdt:P161 "ACTED_IN")
                    (wdt:P58 "WROTE_SCREENPLAY_FOR")
                    (wdt:P162 "PRODUCED")
                  }
                  ?film ?p wd:%s ;
                        wdt:P31/wdt:P279* wd:Q11424 .
                  OPTIONAL { ?film wdt:P577 ?date . BIND(YEAR(?date) AS ?year) }
                  SERVICE wikibase:label { bd:serviceParam wikibase:language "en". }
                } LIMIT %d
                """.formatted(qid, FILMOGRAPHY_LIMIT);

        List<Map<String, String>> rows = select(query, "filmography");

        // Two passes, because a release year is a property of the FILM, not of one credit. The
        // query returns the cross product of roles and P577 statements, so a single pass keyed on
        // (film, role) would give one film two years -- directed in 2002, acted in 2003.
        Map<String, Integer> earliestYear = new LinkedHashMap<>();
        Map<String, String> titles = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String filmQid = localName(row.get("film"));
            if (filmQid == null) continue;
            String title = row.get("filmLabel");
            if (title != null && !title.equals(filmQid)) titles.putIfAbsent(filmQid, title);
            Integer year = parseYearOrNull(row.get("year"));
            if (year != null) earliestYear.merge(filmQid, year, Math::min);
        }

        Map<String, WikidataFilmCredit> byFilmAndRole = new LinkedHashMap<>();
        for (Map<String, String> row : rows) {
            String filmQid = localName(row.get("film"));
            String role = row.get("role");
            if (filmQid == null || role == null) continue;
            // wikibase:label falls back to the raw QID when an item has no English label. That is a
            // title nobody can match a cinema listing against, so it is dropped with a log line
            // rather than persisted as a film called "Q999999".
            String title = titles.get(filmQid);
            if (title == null) {
                log.atDebug().addKeyValue("source", "wikidata").addKeyValue("film", filmQid)
                        .log("film has no English label, dropped");
                continue;
            }
            byFilmAndRole.putIfAbsent(filmQid + "/" + role,
                    new WikidataFilmCredit(filmQid, title, earliestYear.get(filmQid), role));
        }

        List<WikidataFilmCredit> credits = new ArrayList<>(byFilmAndRole.values());
        log.atDebug().addKeyValue("source", "wikidata").addKeyValue("qid", qid)
                .addKeyValue("rows", rows.size()).addKeyValue("credits", credits.size())
                .log("filmography lookup");
        return credits;
    }

    private static Integer parseYearOrNull(String value) {
        if (value == null) return null;
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** {@code http://www.wikidata.org/entity/Q41148} -> {@code Q41148}; null if it is not one. */
    private static String localName(String entityUri) {
        if (entityUri == null || !entityUri.startsWith(ENTITY_PREFIX)) return null;
        String id = entityUri.substring(ENTITY_PREFIX.length());
        return QID.matcher(id).matches() ? id : null;
    }

    /**
     * A name goes into the query as a SPARQL string literal, so a backslash or a quote in it would
     * otherwise end the literal and let the rest of the name be parsed as query syntax. Artists
     * really do have quotes in their names.
     */
    private static String escapeLiteral(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", " ");
    }

    /** Runs a SELECT and flattens each binding row to {var -> value}; empty on any failure. */
    @SuppressWarnings("unchecked")
    private List<Map<String, String>> select(String query, String what) {
        Map<String, Object> body;
        try {
            body = restClient.get()
                    // The query goes in as a URI-template VARIABLE, not as literal text: a SPARQL
                    // body is full of { } braces, and UriComponentsBuilder reads those as template
                    // placeholders -- "Not enough variable values available to expand ' ?film wdt'".
                    .uri(uriBuilder -> uriBuilder.queryParam("query", "{sparql}").build(query))
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            // Empty and a failure are indistinguishable to a caller that only sees a list, so the
            // failure is logged at WARN here -- a silently empty filmography would otherwise look
            // exactly like an artist who never made a film.
            log.atWarn().setCause(e).addKeyValue("source", "wikidata").log(what + " query failed");
            return List.of();
        }
        if (body == null) return List.of();

        Map<String, Object> results = (Map<String, Object>) body.get("results");
        if (results == null) return List.of();
        List<Map<String, Map<String, Object>>> bindings =
                (List<Map<String, Map<String, Object>>>) results.get("bindings");
        if (bindings == null) return List.of();

        List<Map<String, String>> rows = new ArrayList<>(bindings.size());
        for (Map<String, Map<String, Object>> binding : bindings) {
            Map<String, String> row = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Object>> cell : binding.entrySet()) {
                Object value = cell.getValue() == null ? null : cell.getValue().get("value");
                if (value instanceof String text) row.put(cell.getKey(), text);
            }
            rows.add(row);
        }
        return rows;
    }

    /** The role vocabulary this service can emit, mirroring segue's edge types. */
    public static final Set<String> ROLES =
            Set.of("DIRECTED", "ACTED_IN", "WROTE_SCREENPLAY_FOR", "PRODUCED");
}
