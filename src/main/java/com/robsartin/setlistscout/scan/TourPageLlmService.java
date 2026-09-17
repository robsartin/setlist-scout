package com.robsartin.setlistscout.scan;

import com.robsartin.setlistscout.AppProperties;
import com.robsartin.setlistscout.shared.AnthropicMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * LLM fallback for the band-site scraper (#22): when a tour page has no structured JSON-LD
 * events, ask Claude to extract shows from the page text. Returns each show as an
 * {@link ExtractedShow} -- date, venue, city, plus an optional performer and a kind (#208), since
 * the source page may be a venue calendar listing other acts rather than the tracked artist's own
 * tour page. Degrades to empty on any error so a scrape never breaks a scan.
 */
@Service
public class TourPageLlmService {

    private static final Logger log = LoggerFactory.getLogger(TourPageLlmService.class);

    /**
     * Safety bound against a pathological page, not a cost lever (#208) -- the measured median
     * {@code official_site_url} page is 1,646 chars (18-page production sample), and 80% of
     * sampled pages never reach even the old 8,000-char cap. Cap City Comedy Club's real venue
     * calendar (the motivating case, #208) is 149,420 chars and fits comfortably under this.
     */
    private static final int PAGE_TEXT_CHAR_CAP = 200_000;

    /**
     * Sized for a full venue-calendar response, not a typical one (#208), from a live measurement
     * rather than an estimate: the real Cap City Comedy Club calendar extracts to <b>181 shows,
     * 6,906 output tokens</b> ({@code stop_reason: end_turn}), i.e. 38.2 tokens per line, spanning
     * 2026-08-20 to 2027-11-10.
     * <p>
     * An earlier estimate of "~50 shows, 1,100-1,400 tokens" was wrong by more than 3x, and the
     * error is instructive: a comedy club books <b>multi-night runs</b>, so one booked act becomes
     * three or four dated lines. Counting acts badly undercounts shows. At {@code 4000} the real
     * calendar was cut off mid-line after ~105 of 181 shows -- 42% of it silently discarded, with
     * {@code stop_reason: max_tokens} the only trace and every unit test still green, since no test
     * exercises a full-calendar response.
     * <p>
     * 12,000 leaves ~74% headroom over the measured 6,906. Note this is a <b>ceiling, not a
     * charge</b>: a typical band tour page emits a handful of lines and costs a handful of tokens,
     * so raising it does not raise the cost of the common case. Truncation here is the failure mode
     * that looks like success, which is why the bound is generous.
     */
    private static final int MAX_OUTPUT_TOKENS = 12_000;

    private final RestClient restClient;
    private final String apiKey;

    /**
     * Base URL is injectable (#184) rather than hardcoded, so tests can point it at an unroutable
     * address instead of making live calls to Anthropic. Also the test seam a plain unit test uses
     * to point at a local stub server directly, bypassing Spring entirely.
     */
    @Autowired
    public TourPageLlmService(AppProperties props,
            @Value("${setlistscout.tour-page-llm.base-url:https://api.anthropic.com/v1}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("anthropic-version", "2023-06-01")
                .build();
        this.apiKey = props.apis().anthropicApiKey();
    }

    /** Live mode -- the original entry point, unchanged for band sites and live venues. */
    public List<ExtractedShow> extractShows(String artistName, String pageText) {
        return extractShows(artistName, pageText, VenueKind.LIVE);
    }

    /**
     * #284: extraction in one of two modes. A cinema calendar told it is "a tour/shows page
     * belonging to <name>" and asked for "the act performing that date" produces nonsense, so
     * {@link VenueKind#CINEMA} asks a different question and parses a different fifth field --
     * a release year rather than MUSIC/COMEDY -- plus a showtime the live format has no use for.
     *
     * <p>The showtime is not cosmetic. {@code show_event}'s natural key is
     * {@code (owner, artist_name, event_date_time, venue_name)}; two screenings of one film on one
     * day differ by nothing else, so without a time they collapse to a single midnight row and
     * {@code ON CONFLICT DO NOTHING} silently drops the second.
     */
    @SuppressWarnings("unchecked") // Map.class -> Map<String, Object> below is an unchecked conversion
    public List<ExtractedShow> extractShows(String artistName, String pageText, VenueKind venueKind) {
        List<ExtractedShow> result = new ArrayList<>();
        // Cap the text sent to the model -- tour pages can be huge; the dates are near the top.
        String text = pageText.length() > PAGE_TEXT_CHAR_CAP ? pageText.substring(0, PAGE_TEXT_CHAR_CAP) : pageText;
        String prompt = venueKind == VenueKind.CINEMA
                ? cinemaPrompt(artistName, text)
                : livePrompt(artistName, text);

        Map<String, Object> body = Map.of(
                "model", "claude-sonnet-5",
                "max_tokens", MAX_OUTPUT_TOKENS,
                "thinking", AnthropicMessages.THINKING_DISABLED,
                "messages", List.of(Map.of("role", "user", "content", prompt))
        );

        Map<String, Object> response;
        try {
            response = restClient.post()
                    .uri("/messages")
                    .header("x-api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .body(Map.class);
        } catch (Exception e) {
            log.atWarn().setCause(e)
                    .addKeyValue("source", "tour-llm")
                    .addKeyValue("artist", artistName)
                    .log("tour-page extraction request failed");
            response = Map.of();
        }

        Optional<String> responseText = AnthropicMessages.textBlock(response);
        if (responseText.isEmpty()) {
            // Total failure (e.g. thinking consumed the whole output budget) and "this page
            // genuinely has no shows" must not look identical from the outside (#211) -- the
            // latter always produces a text block, even an empty/non-matching one.
            var warn = log.atWarn()
                    .addKeyValue("source", "tour-llm")
                    .addKeyValue("artist", artistName);
            Object stopReason = AnthropicMessages.stopReason(response).orElse(null);
            if (stopReason != null) {
                warn = warn.addKeyValue("stop_reason", stopReason);
            }
            warn.log("tour-page extraction returned no text block");
            return result;
        }

        for (String line : responseText.get().split("\n")) {
            String[] parts = line.split("\\|");
            if (parts.length < 3) continue;
            try {
                // #284: field 1 may carry a showtime ("2026-10-04 19:30") in cinema mode. Split on
                // the first space so the date parse is unchanged for live lines, and so a cinema
                // line that omits its time still yields a screening rather than being skipped.
                String[] when = parts[0].trim().split("\\s+", 2);
                LocalDate date = LocalDate.parse(when[0]);
                LocalTime time = when.length > 1 ? parseTimeOrNull(when[1]) : null;
                String venue = parts[1].trim();
                String city = parts[2].trim();
                // Both trail the original 3-field format (#22); a model that doesn't comply still
                // yields a usable show rather than zero shows -- see class javadoc.
                String performerRaw = parts.length > 3 ? parts[3].trim() : "";
                String performer = performerRaw.isBlank() ? null : performerRaw;
                // #284: the fifth field means different things per mode -- MUSIC/COMEDY for a
                // live page, the film's release year for a cinema. The mode decides how to read it,
                // so one parser serves both without guessing from the content.
                String fifth = parts.length > 4 ? parts[4].trim() : "";
                Show.Kind kind;
                Integer releaseYear;
                if (venueKind == VenueKind.CINEMA) {
                    kind = Show.Kind.FILM;
                    // A year we cannot read is not worth discarding a real screening over; the
                    // column is nullable precisely so a screening can exist without one.
                    releaseYear = parseYearOrNull(fifth);
                } else {
                    kind = fifth.equalsIgnoreCase("COMEDY") ? Show.Kind.COMEDY : Show.Kind.MUSIC;
                    releaseYear = null;
                }
                // #218: a blank venue is kept, not dropped -- some pages (e.g. ASO's own season
                // page) name no venue anywhere, and the prompt above now tells the model to leave
                // this field blank rather than guess one. Dropping the row here would silently
                // discard every such show before BandSiteShowSource ever gets a chance to apply the
                // artist's configured default venue name/city.
                result.add(new ExtractedShow(date, time, venue, city, performer, kind, releaseYear));
            } catch (Exception e) {
                // skip lines that aren't a well-formed "date | venue | city"; one bad line among
                // possibly many good ones, not a whole-request failure, so DEBUG rather than WARN.
                log.atDebug().setCause(e)
                        .addKeyValue("source", "tour-llm")
                        .addKeyValue("artist", artistName)
                        .addKeyValue("line", line)
                        .log("malformed tour-page line, skipping");
            }
        }
        log.atDebug().addKeyValue("source", "tour-llm").addKeyValue("artist", artistName)
                .addKeyValue("count", result.size()).log("tour-page extraction");
        return result;
    }

    /**
     * @param performer the performing act's name, or {@code null} when the model didn't return a
     *     4th field (either an older-style 3-field response, or a genuinely blank field -- both
     *     collapse to {@code null} so callers have one fallback check, not two).
     * @param kind {@link Show.Kind#MUSIC} unless the model's 5th field trims to "COMEDY"
     *     (case-insensitive); also the default when the field is absent.
     */
    public record ExtractedShow(LocalDate date, LocalTime time, String venue, String city,
                                String performer, Show.Kind kind, Integer releaseYear) {

        /** A live show: no showtime (page dates are day-granular) and no release year. */
        static ExtractedShow live(LocalDate date, String venue, String city, String performer, Show.Kind kind) {
            return new ExtractedShow(date, null, venue, city, performer, kind, null);
        }
    }

    /** The original live-show prompt (#22/#208/#218), unchanged. */
    private static String livePrompt(String artistName, String text) {
        return "This is the text of a tour/shows page belonging to \"" + artistName + "\","
                + " which may be a band's own site or a venue's calendar of other acts."
                + " Extract each upcoming live show as one line in exactly this format:\n"
                + "YYYY-MM-DD | Venue name | City | Performer | MUSIC or COMEDY\n"
                + "Performer is the name of the act actually performing that date -- for a band's own"
                + " tour page that is \"" + artistName + "\" itself; for a venue calendar it is whoever"
                + " is booked that date, not the venue. Classify each show as MUSIC or COMEDY.\n"
                + "Some pages give only a month and day for each show, anchored to a season named"
                + " elsewhere on the page (e.g. \"2026-27 Season\"). When that's the case, resolve"
                + " each date's year against the stated season: a month from the season's start"
                + " (roughly autumn) through December takes the season's first year, and a month from"
                + " January through the season's end (roughly summer) takes its second year -- prefer"
                + " an explicit year stated on the page over this inference wherever the two would"
                + " disagree. If you cannot confidently resolve a show's full date this way, omit that"
                + " show entirely rather than guessing its year -- a wrong date is worse than a missing"
                + " show.\n"
                + "A single heading that names more than one date (e.g. a month followed by two day"
                + " numbers, or \"Friday 11 & Sunday 13\") describes more than one show -- emit one"
                + " line per date, never one line for the pair.\n"
                + "If the page does not state a venue for a show, leave the Venue name field blank"
                + " rather than guessing one.\n"
                + "One show per line, no header, no commentary. If there are no shows, return nothing.\n\n"
                + text;
    }

    /**
     * #284: the cinema-calendar prompt. Same five-field pipe shape so one parser serves both, but
     * the questions differ: field 1 carries a showtime, field 4 is the film's title, and field 5 is
     * its RELEASE YEAR rather than MUSIC/COMEDY.
     *
     * <p>The year is asked for because a film's identity is title+year -- repertory cinemas screen
     * {@code Dune (1984)} and {@code Dune (2021)} in one season -- and because sub-project 2 needs
     * it to resolve a WORK. The showtime is asked for because without it two screenings of one film
     * on one day collide on {@code show_event}'s natural key.
     */
    private static String cinemaPrompt(String cinemaName, String text) {
        return "This is the text of a screening calendar for the cinema \"" + cinemaName + "\"."
                + " Every entry is a film showing, not a live performance."
                + " Extract each upcoming screening as one line in exactly this format:\n"
                + "YYYY-MM-DD HH:MM | Cinema name | City | Film title | Release year\n"
                + "Film title is the title of the film being screened, NOT the cinema, NOT a season"
                + " or series name, and NOT a guest introducing it. Use the film's own title.\n"
                + "Release year is the year the FILM was originally released, which for a repertory"
                + " screening is usually not the year it is being shown -- a 2026 screening of"
                + " Goodfellas has release year 1990. If the page does not state it and you are not"
                + " confident, leave the field blank rather than guessing.\n"
                + "Give each screening its own line with its own start time, in 24-hour HH:MM. A film"
                + " shown three times in a day is three lines. If a showing's time is genuinely not"
                + " stated, give the date alone and omit the time rather than inventing one.\n"
                + "Some pages give only a month and day, anchored to a season named elsewhere on the"
                + " page. Resolve each date's year against the stated season, preferring an explicit"
                + " year wherever the two would disagree. If you cannot confidently resolve a"
                + " screening's full date, omit that screening entirely rather than guessing.\n"
                + "One screening per line, no header, no commentary. If there are no screenings,"
                + " return nothing.\n\n"
                + text;
    }

    /** @return the time in {@code HH:MM}, or null when the model gave something unparseable. */
    private static LocalTime parseTimeOrNull(String raw) {
        try {
            return LocalTime.parse(raw.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * @return a plausible four-digit release year, or null. Bounded rather than merely numeric: a
     * stray page number or a runtime in minutes would otherwise be stored as a film's year, and a
     * wrong year is worse than none once sub-project 2 uses title+year as a WORK's identity.
     */
    private static Integer parseYearOrNull(String raw) {
        try {
            int year = Integer.parseInt(raw.trim());
            return (year >= 1880 && year <= LocalDate.now().getYear() + 5) ? year : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
