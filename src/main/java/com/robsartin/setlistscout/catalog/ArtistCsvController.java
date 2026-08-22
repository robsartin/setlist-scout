package com.robsartin.setlistscout.catalog;

import com.robsartin.setlistscout.shared.CsvResponses;
import com.robsartin.setlistscout.shared.CurrentUser;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

/**
 * {@code GET /artists.csv} (issue #228): a CSV export of every one of the owner's artists, ANY
 * status, with {@code status} as its own column -- unlike {@link ArtistController#list}, which
 * shows only the active (SEED/APPROVED) list, the point of an export is to see everything.
 * <p>
 * A separate controller rather than a method on {@link ArtistController}, deliberately: that class
 * carries a class-level {@code @RequestMapping("/artists")}, and Spring MVC always combines a
 * class-level mapping with the method's own -- there is no way to opt one method out of it. A
 * method there could only ever answer {@code /artists/artists.csv}, not the top-level {@code
 * /artists.csv} path the issue asks for (matching {@code /shows.csv} and {@code /shared/{id}.csv}
 * sitting directly off their own pages' routes).
 */
@Controller
public class ArtistCsvController {

    private static final List<String> HEADER = List.of(
            "name", "status", "source", "discovered_via", "official_site_url", "created_at");

    private final ArtistRepository artistRepository;
    private final CurrentUser currentUser;

    public ArtistCsvController(ArtistRepository artistRepository, CurrentUser currentUser) {
        this.artistRepository = artistRepository;
        this.currentUser = currentUser;
    }

    @GetMapping("/artists.csv")
    public ResponseEntity<byte[]> csv() {
        String owner = currentUser.email();
        List<Artist> artists = artistRepository.findByOwner(owner);
        List<List<String>> rows = artists.stream().map(ArtistCsvController::row).toList();
        return CsvResponses.download("artists.csv", HEADER, rows);
    }

    /** One CSV row for {@code artist}, in {@link #HEADER} order. Nullable fields render as "", never the string "null". */
    private static List<String> row(Artist artist) {
        return List.of(
                orBlank(artist.getName()),
                artist.getStatus() == null ? "" : artist.getStatus().name(),
                artist.getSource() == null ? "" : artist.getSource().name(),
                orBlank(artist.getDiscoveredVia()),
                orBlank(artist.getOfficialSiteUrl()),
                artist.getCreatedAt() == null ? "" : artist.getCreatedAt().toString());
    }

    private static String orBlank(String s) {
        return s == null ? "" : s;
    }
}
