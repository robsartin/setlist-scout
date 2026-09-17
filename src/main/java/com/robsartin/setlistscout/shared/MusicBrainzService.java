package com.robsartin.setlistscout.shared;

import com.robsartin.setlistscout.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Looks up "member of band" / "collaborator" relationships via the MusicBrainz API.
 * MusicBrainz requires a descriptive User-Agent identifying the application and a
 * contact -- see https://musicbrainz.org/doc/MusicBrainz_API/Rate_Limiting.
 * Rate limit: ~1 request/second for unauthenticated use, so calls here are sequential
 * on purpose -- don't parallelize without adding a rate limiter.
 */
@Service
public class MusicBrainzService {

    private static final Logger log = LoggerFactory.getLogger(MusicBrainzService.class);

    private static final Pattern QID = Pattern.compile("Q[0-9]+");

    private final RestClient restClient;
    private final long rateLimitMillis;

    /** Base URL is injectable (#184) so tests can point it at an unroutable address instead of
     * making live calls to MusicBrainz. */
    @Autowired
    public MusicBrainzService(AppProperties props,
            @Value("${setlistscout.musicbrainz.base-url:https://musicbrainz.org/ws/2}") String baseUrl) {
        this(props, baseUrl, 1100);
    }

    /** Test seam: local stub server and a near-zero rate-limit delay. */
    public MusicBrainzService(AppProperties props, String baseUrl, long rateLimitMillis) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.USER_AGENT, props.apis().musicBrainzUserAgent())
                .build();
        this.rateLimitMillis = rateLimitMillis;
    }

    /** Returns related artist/band names (members, ex-members, collaborators, solo projects). */
    @SuppressWarnings("unchecked")
    public List<String> findRelatedArtists(String artistName) {
        List<String> related = new ArrayList<>();

        java.util.Optional<String> found = findMbid(artistName);
        if (found.isEmpty()) return related;
        String mbid = found.get();
        sleepForRateLimit();

        Map<String, Object> detail;
        try {
            detail = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/artist/" + mbid)
                            .queryParam("inc", "artist-rels")
                            .queryParam("fmt", "json")
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "musicbrainz")
                    .addKeyValue("artist", artistName)
                    .log("related-artists lookup failed");
            detail = Map.of();
        }

        if (detail == null) return related;
        List<Map<String, Object>> relations = (List<Map<String, Object>>) detail.get("relations");
        if (relations == null) return related;

        for (Map<String, Object> rel : relations) {
            String type = (String) rel.get("type"); // e.g. "member of band", "collaboration"
            Map<String, Object> otherArtist = (Map<String, Object>) rel.get("artist");
            if (otherArtist == null) continue;
            String name = (String) otherArtist.get("name");
            if (name != null && !name.equalsIgnoreCase(artistName)) {
                related.add(name);
            }
        }
        log.atDebug().addKeyValue("source", "musicbrainz").addKeyValue("artist", artistName)
                .addKeyValue("count", related.size()).log("related artists lookup");
        return related;
    }

    /** The artist's official homepage URL from MusicBrainz's url-rels, if one is recorded. */
    public java.util.Optional<String> findOfficialHomepage(String artistName) {
        java.util.Optional<String> result =
                urlRelationResource(artistName, "official homepage", "official homepage lookup");
        log.atDebug().addKeyValue("source", "musicbrainz").addKeyValue("artist", artistName)
                .addKeyValue("found", result.isPresent()).log("official homepage lookup");
        return result;
    }

    /**
     * The artist's Wikidata QID from MusicBrainz's curated {@code wikidata} url-rel (#286).
     *
     * <p>Preferred over searching Wikidata by name, and the reason is disambiguation rather than
     * convenience: {@code wbsearchentities} for "Martin Scorsese" returns the filmmaker first and a
     * 1993 King Missile <em>song</em> second, and nothing in the response says which one the caller
     * meant. MusicBrainz's link is a human-curated assertion about <em>this</em> artist, so a hit
     * here needs no guard. It rides the same {@code inc=url-rels} request
     * {@link #findOfficialHomepage} already makes, so it costs one call, not a new integration.
     *
     * <p>Empty for anyone MusicBrainz does not carry -- a film director who never recorded -- which
     * is what the name-search fallback in {@code catalog.WikidataIdentityService} exists for.
     */
    public java.util.Optional<String> findWikidataQid(String artistName) {
        java.util.Optional<String> result = urlRelationResource(artistName, "wikidata", "wikidata QID lookup")
                .flatMap(MusicBrainzService::qidFromEntityUrl);
        log.atDebug().addKeyValue("source", "musicbrainz").addKeyValue("artist", artistName)
                .addKeyValue("found", result.isPresent()).log("wikidata QID lookup");
        return result;
    }

    /**
     * {@code https://www.wikidata.org/wiki/Q206112} -> {@code Q206112}.
     *
     * <p>Shape-checked rather than taken as given: the last path segment must be {@code Q} followed
     * by digits. MusicBrainz's data is user-edited, and a {@code wikidata} relation pointing at
     * {@code Special:Search} or a property would otherwise be stored as this artist's identity and
     * used to attach a filmography -- the failure mode that no later step can detect, because a
     * well-formed wrong QID and a right one are indistinguishable downstream.
     */
    private static java.util.Optional<String> qidFromEntityUrl(String resource) {
        String segment = resource.substring(resource.lastIndexOf('/') + 1);
        return QID.matcher(segment).matches() ? java.util.Optional.of(segment) : java.util.Optional.empty();
    }

    /** The {@code resource} of the first url-rel of the given type, if the artist and relation exist. */
    @SuppressWarnings("unchecked")
    private java.util.Optional<String> urlRelationResource(String artistName, String relationType, String what) {
        java.util.Optional<String> mbid = findMbid(artistName);
        if (mbid.isEmpty()) return java.util.Optional.empty();
        sleepForRateLimit();

        Map<String, Object> detail;
        try {
            detail = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/artist/" + mbid.get())
                            .queryParam("inc", "url-rels")
                            .queryParam("fmt", "json")
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "musicbrainz")
                    .addKeyValue("artist", artistName)
                    .log(what + " failed");
            return java.util.Optional.empty();
        }

        if (detail == null) return java.util.Optional.empty();
        List<Map<String, Object>> relations = (List<Map<String, Object>>) detail.get("relations");
        if (relations == null) return java.util.Optional.empty();

        for (Map<String, Object> rel : relations) {
            if (relationType.equals(rel.get("type"))) {
                Map<String, Object> url = (Map<String, Object>) rel.get("url");
                if (url != null && url.get("resource") instanceof String resource) {
                    return java.util.Optional.of(resource);
                }
            }
        }
        return java.util.Optional.empty();
    }

    /** The MBID of the best search hit for this name, if MusicBrainz knows the artist at all. */
    @SuppressWarnings("unchecked")
    private java.util.Optional<String> findMbid(String artistName) {
        Map<String, Object> searchResult;
        try {
            searchResult = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/artist/")
                            .queryParam("query", "artist:\"" + artistName + "\"")
                            .queryParam("fmt", "json")
                            .queryParam("limit", 1)
                            .build())
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "musicbrainz")
                    .addKeyValue("artist", artistName)
                    .log("artist search failed");
            return java.util.Optional.empty();
        }

        if (searchResult == null) return java.util.Optional.empty();
        List<Map<String, Object>> artists = (List<Map<String, Object>>) searchResult.get("artists");
        if (artists == null || artists.isEmpty()) return java.util.Optional.empty();
        return java.util.Optional.ofNullable((String) artists.get(0).get("id"));
    }

    private void sleepForRateLimit() {
        try {
            Thread.sleep(rateLimitMillis); // stay under MusicBrainz's ~1 req/sec limit
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
