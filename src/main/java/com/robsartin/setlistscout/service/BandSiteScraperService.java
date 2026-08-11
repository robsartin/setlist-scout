package com.robsartin.setlistscout.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robsartin.setlistscout.domain.Show;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Scrapes a band's official site for tour dates (#22). Hybrid extraction: structured
 * schema.org Event JSON-LD first (which many band/venue sites and embedded widgets provide),
 * falling back to an LLM read of the page text for free-form pages. Any network or parse
 * failure yields no shows -- a scrape never breaks the wider scan.
 */
@Service
public class BandSiteScraperService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TIMEOUT_MS = 10_000;
    private static final String USER_AGENT = "SetlistScout/0.1 (+https://setlist-scout.onrender.com)";

    private final TourPageLlmService tourPageLlm;

    @Autowired
    public BandSiteScraperService(TourPageLlmService tourPageLlm) {
        this.tourPageLlm = tourPageLlm;
    }

    public List<Show> scrapeShows(String artistName, String siteUrl, LocalDateTime start, LocalDateTime end) {
        try {
            Document doc = Jsoup.connect(siteUrl).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
            List<Show> shows = extractShows(artistName, doc, siteUrl, start, end);
            if (shows.isEmpty()) {
                String tourUrl = findTourPageUrl(doc);
                if (tourUrl != null && !tourUrl.equals(siteUrl)) {
                    Document tourDoc = Jsoup.connect(tourUrl).userAgent(USER_AGENT).timeout(TIMEOUT_MS).get();
                    shows = extractShows(artistName, tourDoc, tourUrl, start, end);
                }
            }
            return shows;
        } catch (Exception e) {
            return List.of(); // network/parse/blocked -> no shows, never breaks the scan
        }
    }

    /** Extract shows from an already-fetched page: JSON-LD events first, LLM fallback otherwise. */
    List<Show> extractShows(String artistName, Document doc, String pageUrl, LocalDateTime start, LocalDateTime end) {
        String source = "band-site:" + domainOf(pageUrl);
        List<Show> shows = new ArrayList<>();

        for (Element script : doc.select("script[type=application/ld+json]")) {
            for (JsonNode event : eventsIn(script.data())) {
                Show show = toShow(artistName, event, source, pageUrl);
                if (show != null && inWindow(show, start, end)) shows.add(show);
            }
        }

        if (shows.isEmpty()) {
            for (TourPageLlmService.ExtractedShow es : tourPageLlm.extractShows(artistName, doc.text())) {
                LocalDateTime dt = es.date().atStartOfDay();
                if (!dt.isBefore(start) && !dt.isAfter(end)) {
                    shows.add(new Show(artistName, dt, es.venue(), es.city(), null, source, pageUrl));
                }
            }
        }
        return shows;
    }

    private List<JsonNode> eventsIn(String json) {
        List<JsonNode> events = new ArrayList<>();
        try {
            collectEvents(MAPPER.readTree(json), events);
        } catch (Exception e) {
            // malformed ld+json -> skip this block
        }
        return events;
    }

    private void collectEvents(JsonNode node, List<JsonNode> out) {
        if (node.isArray()) {
            node.forEach(n -> collectEvents(n, out));
        } else if (node.isObject()) {
            if (node.has("@graph")) collectEvents(node.get("@graph"), out);
            String type = typeOf(node);
            if ("Event".equals(type) || "MusicEvent".equals(type)) out.add(node);
        }
    }

    private String typeOf(JsonNode node) {
        JsonNode t = node.get("@type");
        if (t == null) return null;
        if (t.isTextual()) return t.asText();
        if (t.isArray()) {
            for (JsonNode x : t) if (x.isTextual()) return x.asText();
        }
        return null;
    }

    private Show toShow(String artistName, JsonNode event, String source, String pageUrl) {
        JsonNode startNode = event.get("startDate");
        if (startNode == null || !startNode.isTextual()) return null;
        LocalDateTime dt;
        try {
            String s = startNode.asText();
            dt = s.length() > 10
                    ? LocalDateTime.parse(s, DateTimeFormatter.ISO_DATE_TIME)
                    : LocalDate.parse(s).atStartOfDay();
        } catch (Exception e) {
            return null;
        }
        JsonNode loc = event.get("location");
        String venue = (loc != null && loc.hasNonNull("name")) ? loc.get("name").asText() : "Unknown venue";
        String city = cityOf(loc);
        String url = event.hasNonNull("url") ? event.get("url").asText() : pageUrl;
        return new Show(artistName, dt, venue, city, null, source, url);
    }

    private String cityOf(JsonNode loc) {
        if (loc == null) return null;
        JsonNode addr = loc.get("address");
        if (addr == null) return null;
        if (addr.isTextual()) return addr.asText();
        if (addr.hasNonNull("addressLocality")) return addr.get("addressLocality").asText();
        return null;
    }

    private boolean inWindow(Show show, LocalDateTime start, LocalDateTime end) {
        LocalDateTime dt = show.getEventDateTime();
        return dt != null && !dt.isBefore(start) && !dt.isAfter(end);
    }

    private String findTourPageUrl(Document doc) {
        for (Element a : doc.select("a[href]")) {
            String haystack = (a.text() + " " + a.attr("href")).toLowerCase();
            if (haystack.matches(".*(tour|shows|dates|concerts|live|events).*")) {
                return a.absUrl("href");
            }
        }
        return null;
    }

    private String domainOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Exception e) {
            return "unknown";
        }
    }
}
