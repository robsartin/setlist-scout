package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * https://developer.ticketmaster.com/products-and-docs/apis/discovery-api/v2/
 * Free tier: 5000 calls/day, ~5 req/sec. Filters by a {@code geoPoint} geohash built from the
 * owner's already-geocoded lat/long (ADR-0018) plus a radius; falls back to {@code postalCode}
 * only when geocoding failed, since Ticketmaster's postal-code index doesn't cover every ZIP and
 * silently returns zero results rather than an error for the ones it misses (#152).
 */
@Service
public class TicketmasterService {

    private static final Logger log = LoggerFactory.getLogger(TicketmasterService.class);

    /** ~5m resolution; verified working against the live Ticketmaster API for #152. */
    private static final int GEOHASH_PRECISION = 9;

    /** Ticketmaster classification names read from each event's {@code classifications} entry (#202). */
    private static final String SEGMENT_MUSIC = "Music";
    private static final String SEGMENT_ARTS_THEATRE = "Arts & Theatre";
    private static final String GENRE_COMEDY = "Comedy";

    /** #207: tokens = maximal runs of {@code [a-z0-9]} after lowercasing; everything else is a separator. */
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[a-z0-9]+");

    /**
     * #207: tribute/homage markers that block the title fallback in {@link #hasMatchingAttraction},
     * pre-tokenized so punctuation in the title can't evade them (e.g. "Salute-To:" still matches
     * {@code salute to}). Deliberately excludes {@code featuring}/{@code ft} -- see the Javadoc on
     * {@link #hasMatchingAttraction} for why.
     */
    private static final List<List<String>> TRIBUTE_MARKER_TOKENS = List.of(
            List.of("tribute"),
            List.of("celebration"),
            List.of("celebrating"),
            List.of("salute", "to"),
            List.of("homage"),
            List.of("the", "music", "of"),
            List.of("songs", "of"),
            List.of("gospel", "of"));

    private final RestClient restClient;
    private final String apiKey;

    @Autowired
    public TicketmasterService(AppProperties props) {
        this(props, "https://app.ticketmaster.com/discovery/v2");
    }

    /** Test seam: points at a local stub server instead of the real Ticketmaster API. */
    TicketmasterService(AppProperties props, String baseUrl) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.apiKey = props.apis().ticketmasterApiKey();
    }

    /**
     * @param postalCode fallback location filter, sent only when {@code latitude}/{@code longitude}
     *                    are null (geocoding failed for this owner -- see ADR-0018/#152)
     * @param latitude    geocoded search-origin latitude, or null
     * @param longitude   geocoded search-origin longitude, or null
     */
    @SuppressWarnings("unchecked")
    public List<Show> searchShows(String artistName, String postalCode, Double latitude, Double longitude,
                                   int radiusMiles, LocalDateTime start, LocalDateTime end) {
        List<Show> shows = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

        Map<String, Object> response;
        try {
            response = restClient.get()
                    .uri(uriBuilder -> {
                        uriBuilder.path("/events.json")
                                .queryParam("apikey", apiKey)
                                .queryParam("keyword", artistName);
                        // Ticketmaster's postalCode index only covers ZIPs where it has market
                        // presence and silently matches nothing for the ones it doesn't (#152) --
                        // geoPoint works from raw coordinates, so prefer it whenever the owner's
                        // ZIP geocoded successfully. Only fall back to postalCode (degraded, but
                        // no worse than before #152) when it didn't; never search with no location
                        // filter at all, which would flood the owner with shows from anywhere.
                        if (latitude != null && longitude != null) {
                            uriBuilder.queryParam("geoPoint",
                                    Geohash.encode(latitude, longitude, GEOHASH_PRECISION));
                        } else {
                            uriBuilder.queryParam("postalCode", postalCode);
                        }
                        // #202: classificationName sent TWICE (music and comedy), not omitted --
                        // NOT the "one broader query, filtered on return" this comment originally
                        // described. That first version dropped classificationName entirely and
                        // relied on classify() below to sort the response out; it turned out to
                        // have a real crowd-out bug, caught in review before this shipped further:
                        // Ticketmaster's default page size is 20, and this catalog is full of
                        // artist names that collide with other segments -- Chicago, Boston,
                        // Phoenix, Kansas, Europe, Alabama are all real tracked bands. With no
                        // classificationName at all, EVERY segment competes for those 20 slots
                        // server-side, ranked by Ticketmaster's own relevance -- not just the
                        // Music/Comedy ones this app wants. Verified live (GET against the real
                        // API, Ticketmaster's own public tutorial demo key, 2026-08-19):
                        // keyword=Chicago with no classificationName returned 20/20 Sports events
                        // (Bulls, Blackhawks, Bears) -- ZERO music, even though "Chicago & Styx:
                        // The Windy Cities Tour" is real, current, and ranks #1-2 the moment
                        // classificationName=music narrows the field. hasMatchingAttraction can
                        // only filter what's IN the response; a show crowded off the first page
                        // never arrives to be filtered, so this was a silent recall regression --
                        // fewer real shows found, nothing in the logs to show it.
                        //
                        // The fix is one call, not two: classificationName is documented as an
                        // array-type parameter, and repeating it is a real OR filter, confirmed
                        // against the live API for the same keyword -- totalElements rose from
                        // 1215 (music alone) to 1676 (music+comedy, ~= 1215+467 comedy alone,
                        // ruling out AND, which would be 0), and the returned page was 100%
                        // Music/Comedy, zero Sports. So this keeps the ORIGINAL #202 concern
                        // solved too (TicketmasterShowSource runs once per (artist, source) with
                        // ~6,400 scan jobs; a genuine second HTTP round trip per artist would still
                        // double load against the rate-limited free-tier key) while also closing
                        // the crowd-out gap a single call cost nothing extra to close.
                        //
                        // classify() below still reads each event's OWN classifications array to
                        // label and double-check it, rather than trusting "it came back from a
                        // music+comedy-filtered query" -- classificationName's own docs describe it
                        // as matching against segment/genre/sub-genre/type/sub-type names, loosely
                        // enough that what comes back isn't guaranteed to be cleanly one or the
                        // other, and hasMatchingAttraction's fuzzy keyword match means an event
                        // being returned at all was never proof it's about what was searched.
                        return uriBuilder
                                .queryParam("classificationName", "music", "comedy")
                                .queryParam("radius", radiusMiles)
                                .queryParam("unit", "miles")
                                .queryParam("startDateTime", start.format(fmt))
                                .queryParam("endDateTime", end.format(fmt))
                                .build();
                    })
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "ticketmaster")
                    .addKeyValue("artist", artistName)
                    .log("show search failed");
            response = Map.of();
        }

        Map<String, Object> embedded = response != null
                ? (Map<String, Object>) response.get("_embedded") : null;
        List<Map<String, Object>> events = embedded != null
                ? (List<Map<String, Object>>) embedded.get("events") : null;

        // Ticketmaster omits "_embedded" entirely on a legitimate zero-result search (not an
        // error), so events being null here just means "nothing matched" -- fall through to the
        // log-then-return below rather than early-returning past it.
        if (events != null) {
            for (Map<String, Object> event : events) {
                if (!hasMatchingAttraction(artistName, event)) continue;
                // #202: classificationName=music,comedy narrows the server-side result, but its
                // own docs describe it as a loose match across segment/genre/sub-genre/type/
                // sub-type names, not a strict segment filter -- classify() re-checks each event's
                // actual classifications and returns null for anything that isn't cleanly Music or
                // Arts & Theatre/Comedy (Film (#204), other Arts & Theatre genres, or a stray
                // Sports/Misc match), which drops the event here rather than mislabeling it.
                Show.Kind kind = classify(event);
                if (kind == null) continue;
                shows.add(parseEvent(artistName, event, kind));
            }
        }
        log.atDebug().addKeyValue("source", "ticketmaster").addKeyValue("artist", artistName)
                .addKeyValue("count", shows.size()).log("show search");
        return shows;
    }

    /**
     * Ticketmaster's {@code keyword} query is a fuzzy relevance search, so it can return events
     * that only loosely resemble the searched artist. Only trust an event if the searched artist
     * is actually one of the billed attractions -- exact {@code trim().toLowerCase()} equality
     * against each attraction name, unchanged since before #207.
     * <p>
     * #207: that exact match only runs when {@code attractions} is non-empty; a populated-but-
     * non-matching array returns {@code false} right there and never reaches the fallback below --
     * pinned by {@code TicketmasterServiceTest}'s "critical regression guard" case. The fallback
     * runs ONLY when Ticketmaster linked NO attraction at all (the array is null or empty), which
     * measured 19 of 137 returned events (13.9%) across 60 random tracked artists -- including the
     * motivating case, "A Very Merry Symphony ft. Austin Symphony Orchestra", genuinely
     * attraction-less on both the search AND event-detail endpoints, and dropped before #207 even
     * though "Austin Symphony Orchestra" is right there in the title.
     * <p>
     * The fallback keeps the event only when ALL of:
     * <ol>
     *   <li>the artist name tokenizes to two or more tokens -- this catalog tracks single-word
     *       artist names that double as place/common-word names (Chicago, Boston, Phoenix,
     *       Kansas, Europe, Alabama, Austin), so without this guard an attraction-less "New
     *       Year's Eve in Austin" would match the artist "Austin";</li>
     *   <li>the artist's tokens appear as a CONSECUTIVE run in the event title's tokens -- so
     *       "Tommy Emmanuel" matches inside "Tommy Emmanuel, CGP - Living In The Light Tour"
     *       (punctuation can't break the run), but "Austin Symphony Orchestra" does not match
     *       "Some Unrelated Show"; and</li>
     *   <li>the title carries no tribute/homage marker. Measured: of the 19 empty-attraction
     *       events, the first two rules alone rescued 8, and 6 of those 8 were tribute acts for
     *       dead artists -- Chris Cornell, Jimi Hendrix -- that would otherwise land on the page
     *       labelled as the artist themselves, which is worse than the missed show this fallback
     *       exists to fix. {@code featuring}/{@code ft} is deliberately NOT a marker: the
     *       motivating event above is titled "... ft. Austin Symphony Orchestra", and marking it
     *       would break the exact case #207 exists to rescue.</li>
     * </ol>
     * Error bias is deliberate: a false negative here is just today's status quo, a silently
     * missed show. A false positive puts a show on the page under the wrong artist and erodes
     * trust in every other row. Where uncertain, drop -- a real artist whose tour is genuinely
     * titled "... Celebration" will be missed; that's the accepted trade.
     */
    @SuppressWarnings("unchecked")
    private boolean hasMatchingAttraction(String artistName, Map<String, Object> event) {
        Map<String, Object> embedded = (Map<String, Object>) event.get("_embedded");
        List<Map<String, Object>> attractions = embedded != null
                ? (List<Map<String, Object>>) embedded.get("attractions") : null;

        if (attractions != null && !attractions.isEmpty()) {
            String normalizedSearch = artistName.trim().toLowerCase();
            for (Map<String, Object> attraction : attractions) {
                if (attraction.get("name") instanceof String name
                        && name.trim().toLowerCase().equals(normalizedSearch)) {
                    return true;
                }
            }
            return false;
        }

        return matchesTitleFallback(artistName, (String) event.get("name"));
    }

    /** #207: only reached when {@code attractions} is null/empty -- see the Javadoc on {@link #hasMatchingAttraction}. */
    private boolean matchesTitleFallback(String artistName, String eventTitle) {
        if (eventTitle == null) return false;

        List<String> artistTokens = tokenize(artistName);
        if (artistTokens.size() < 2) return false;

        List<String> titleTokens = tokenize(eventTitle);
        return containsConsecutiveRun(titleTokens, artistTokens) && !containsTributeMarker(titleTokens);
    }

    /** #207: true if any tribute/homage marker appears as a consecutive run in {@code titleTokens}. */
    private static boolean containsTributeMarker(List<String> titleTokens) {
        for (List<String> marker : TRIBUTE_MARKER_TOKENS) {
            if (containsConsecutiveRun(titleTokens, marker)) return true;
        }
        return false;
    }

    /** #207: true if {@code needle} appears as a contiguous, in-order run inside {@code haystack}. */
    private static boolean containsConsecutiveRun(List<String> haystack, List<String> needle) {
        if (needle.isEmpty() || needle.size() > haystack.size()) return false;
        for (int i = 0; i <= haystack.size() - needle.size(); i++) {
            if (haystack.subList(i, i + needle.size()).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    /** #207: tokens = maximal runs of {@code [a-z0-9]} after lowercasing, so e.g. "Emmanuel," and "ft." tokenize clean. */
    private static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text.toLowerCase());
        while (matcher.find()) {
            tokens.add(matcher.group());
        }
        return tokens;
    }

    /**
     * Reads Ticketmaster's own per-event classification (#202) instead of assuming every result
     * is music: {@code classifications} is an array of {@code {segment, genre, subGenre, ...}}
     * entries, at most one marked {@code "primary": true}. Returns {@link Show.Kind#MUSIC} for the
     * Music segment, {@link Show.Kind#COMEDY} for Arts & Theatre with a Comedy genre or sub-genre,
     * and {@code null} for everything else -- Sports, Film (#204), Miscellaneous, and every other
     * Arts & Theatre genre (theatre, dance, magic, ...) are out of scope for #202, and {@code null}
     * tells the caller to drop the event rather than mislabel it.
     * <p>
     * Missing/empty classifications default to MUSIC rather than {@code null}/dropped: before
     * #202 every result was forced music-only by the query itself, so a show that would have
     * matched then must not silently disappear now just because this one field is absent --
     * Ticketmaster documents {@code classifications} as a core field, but not a guaranteed one.
     */
    @SuppressWarnings("unchecked")
    private Show.Kind classify(Map<String, Object> event) {
        List<Map<String, Object>> classifications = (List<Map<String, Object>>) event.get("classifications");
        if (classifications == null || classifications.isEmpty()) {
            return Show.Kind.MUSIC;
        }
        Map<String, Object> classification = primaryClassification(classifications);
        String segment = classificationName(classification, "segment");
        if (SEGMENT_MUSIC.equalsIgnoreCase(segment)) {
            return Show.Kind.MUSIC;
        }
        if (SEGMENT_ARTS_THEATRE.equalsIgnoreCase(segment)
                && (GENRE_COMEDY.equalsIgnoreCase(classificationName(classification, "genre"))
                    || GENRE_COMEDY.equalsIgnoreCase(classificationName(classification, "subGenre")))) {
            return Show.Kind.COMEDY;
        }
        return null;
    }

    /** The {@code "primary": true} entry, or the first when none is marked (rare, but not contractually guaranteed). */
    private static Map<String, Object> primaryClassification(List<Map<String, Object>> classifications) {
        for (Map<String, Object> classification : classifications) {
            if (Boolean.TRUE.equals(classification.get("primary"))) {
                return classification;
            }
        }
        return classifications.get(0);
    }

    /** Reads {@code classification.<key>.name}, e.g. {@code classification.segment.name}. */
    @SuppressWarnings("unchecked")
    private static String classificationName(Map<String, Object> classification, String key) {
        if (classification.get(key) instanceof Map<?, ?> nested && nested.get("name") instanceof String name) {
            return name;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private Show parseEvent(String artistName, Map<String, Object> event, Show.Kind kind) {
        Map<String, Object> dates = (Map<String, Object>) event.get("dates");
        Map<String, Object> start = dates != null ? (Map<String, Object>) dates.get("start") : null;
        LocalDateTime eventDateTime = parseStartDateTime(start);

        Map<String, Object> embedded = (Map<String, Object>) event.get("_embedded");
        List<Map<String, Object>> venues = embedded != null ? (List<Map<String, Object>>) embedded.get("venues") : null;
        Map<String, Object> venue = (venues != null && !venues.isEmpty()) ? venues.get(0) : Map.of();
        String venueName = (String) venue.getOrDefault("name", "Unknown venue");
        Map<String, Object> cityMap = (Map<String, Object>) venue.get("city");
        String venueCity = cityMap != null ? (String) cityMap.get("name") : null;

        List<Map<String, Object>> priceRanges = (List<Map<String, Object>>) event.get("priceRanges");
        BigDecimal price = null;
        if (priceRanges != null && !priceRanges.isEmpty()) {
            Object min = priceRanges.get(0).get("min");
            if (min instanceof Number n) price = BigDecimal.valueOf(n.doubleValue());
        }

        String url = (String) event.get("url");

        String eventName = (String) event.get("name");
        String label = (eventName != null && !eventName.isBlank()) ? eventName : artistName;

        return new Show(label, eventDateTime, venueName, venueCity, price, "ticketmaster", url, kind);
    }

    /**
     * Builds the show time from Ticketmaster's {@code dates.start} block.
     * <p>
     * The {@code dateTime} field is UTC (trailing Z); parsing it with a local
     * formatter keeps the UTC wall-clock time, so an evening show reads as the
     * small hours of the next morning. Ticketmaster also exposes the correct
     * venue-local wall-clock as {@code localDate} + {@code localTime}, so we
     * prefer those when both are present and only fall back to the UTC
     * {@code dateTime} when the local fields are absent.
     */
    private LocalDateTime parseStartDateTime(Map<String, Object> start) {
        if (start == null) return null;

        String localDate = (String) start.get("localDate");
        String localTime = (String) start.get("localTime");
        if (localDate != null && localTime != null) {
            return LocalDateTime.parse(localDate + "T" + localTime, DateTimeFormatter.ISO_DATE_TIME);
        }

        String dateTimeStr = (String) start.get("dateTime");
        return dateTimeStr != null
                ? LocalDateTime.parse(dateTimeStr, DateTimeFormatter.ISO_DATE_TIME)
                : null;
    }
}
