package com.robsartin.setlistscout.catalog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/**
 * Parses an uploaded artist-name file and QUEUES each distinct name into {@code artist_import}
 * (#177), instead of the old {@code ArtistController#upload}'s behavior of calling {@code
 * ArtistSeedService#addSeedIfNew} synchronously per line inside the HTTP request -- the thing
 * that 502'd on a real 1,138-name file, killed part-way by Render's free-tier idle spin-down
 * having imported only 79 names. {@link ArtistImportPoller} drains the queue afterward, off the
 * request thread entirely.
 */
@Service
public class ArtistImportService {

    /** Cap lines read from an uploaded artist file -- a guardrail against a runaway upload. */
    static final int MAX_UPLOAD_LINES = 2000;

    private final ArtistImportRepository artistImportRepository;

    public ArtistImportService(ArtistImportRepository artistImportRepository) {
        this.artistImportRepository = artistImportRepository;
    }

    /**
     * Reads at most {@link #MAX_UPLOAD_LINES} lines from {@code reader}: trims each, skips blanks
     * and {@code #} comments, dedupes WITHIN the file by {@link ArtistNameNormalizer#normalize}
     * form (a 1,138-name export can list the same artist twice under different capitalization --
     * no reason to queue it twice), and queues each surviving name via {@link
     * ArtistImportRepository#insertIfAbsent}.
     * <p>
     * Idempotent re-upload is handled entirely by the database: {@code insertIfAbsent}'s {@code ON
     * CONFLICT DO NOTHING} against the partial unique index {@code artist_import_pending_key}
     * ({@code (owner, normalized_name) WHERE status = 'PENDING'}) makes re-queueing a name that is
     * already PENDING a harmless no-op. This method deliberately does NOT pre-check existence
     * itself (no {@code existsBy} call) -- a read-then-write would race, and the DB-level conflict
     * is this codebase's standing rule for idempotent inserts (see {@code
     * ArtistSeedService#addSeedIfNew}'s identical reasoning for the {@code artist} table). It just
     * sums {@code insertIfAbsent}'s own return values.
     * <p>
     * {@code @Transactional} because {@code insertIfAbsent} is a {@code @Modifying} native query
     * and needs an ambient transaction, exactly like every other {@code insertIfAbsent} call in
     * this codebase.
     *
     * @return how many names were newly queued (rows {@code insertIfAbsent} actually inserted --
     * not lines read, and not counting within-file duplicates or names already PENDING)
     */
    @Transactional
    public int queue(String owner, BufferedReader reader) throws IOException {
        Instant now = Instant.now();
        Set<String> seenNormalized = new HashSet<>();
        int queued = 0;
        String line;
        int seen = 0;
        while ((line = reader.readLine()) != null && seen < MAX_UPLOAD_LINES) {
            seen++;
            String name = line.trim();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            String normalized = ArtistNameNormalizer.normalize(name);
            if (!seenNormalized.add(normalized)) {
                continue;
            }
            queued += artistImportRepository.insertIfAbsent(owner, name, normalized, now, now);
        }
        return queued;
    }
}
