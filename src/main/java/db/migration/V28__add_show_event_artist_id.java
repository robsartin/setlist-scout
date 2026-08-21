package db.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Issue #223: gives {@code show_event} a real {@code artist_id}, so "the artist behind this show"
 * is a stored fact rather than something reconstructed by matching {@code artist_name} against the
 * catalog. Matching by name fails on the majority of Ticketmaster rows: {@code TicketmasterService}
 * stores the EVENT TITLE in {@code artist_name} (label is the event name, falling back to the
 * artist name only when blank) -- a real production row reads
 * "A Very Merry Symphony ft. Austin Symphony Orchestra", which matches nothing in the catalog.
 *
 * <h2>Nullable, permanently</h2>
 * Some rows are genuinely unresolvable (see below), and {@link
 * com.robsartin.setlistscout.scan.VenueScanRunner} has no single scanning artist for a whole
 * venue calendar, so a fresh venue-sourced row can also land null the first time its performer is
 * seen, before {@code catalog.VenuePerformerListener}'s async candidate-creation has landed.
 *
 * <h2>{@code ON DELETE SET NULL}</h2>
 * Chosen over {@code RESTRICT}/{@code NO ACTION} (the implicit default) and {@code CASCADE}.
 * {@code db.migration.support.DuplicateArtistMerger} (V13, V21) already hard-deletes the "loser"
 * row of a duplicate-artist merge via a plain {@code DELETE FROM artist WHERE id = ?} -- a real,
 * exercised path in this codebase, not a hypothetical. {@code RESTRICT} would make any FUTURE
 * merge migration that touches an artist with linked shows fail outright unless it's taught about
 * this new column too; {@code CASCADE} would silently delete real, otherwise-untouched show rows
 * out from under the owner. {@code SET NULL} lets an artist merge/cleanup proceed with no change
 * to {@code DuplicateArtistMerger} at all, and degrades a show whose artist got merged/deleted back
 * to exactly the same "genuinely unresolvable, for now" state a never-matched row is already in --
 * consistent with the column being permanently nullable rather than a data-loss event.
 *
 * <h2>Why a Java migration, not SQL</h2>
 * Same reason as {@code V13__merge_duplicate_variant_artists} and
 * {@code V19__add_artist_normalized_name}: {@link ArtistNameNormalizer#normalize(String)} folds
 * unicode dashes/quotes and hyphen-adjacent whitespace with explicit rules that a hand-rolled SQL
 * approximation could silently drift from -- #118 has a documented case where exactly that drift
 * inflated a true count of 3 pairs to a false 13. This backfill calls the real normalizer in Java
 * and looks the result up against {@code artist.normalized_name} (populated since V19, unique per
 * owner since V21) -- one definition of "same name", not a second one.
 *
 * <h2>Acceptance criteria (profiled read-only against production 2026-08-21, issue #223)</h2>
 * <pre>
 *   source        shows  expect match  expect null
 *   venue           172           172            0
 *   band-site         6             6            0
 *   ticketmaster     49           ~14          ~35
 *   total           227          ~192          ~35
 * </pre>
 * Those counts came from an approximate SQL normalization (sizing only); the real Java backfill may
 * differ by a row or two -- what must hold is venue/band-site matching 100%, every null being a
 * {@code ticketmaster} row, and the total row count staying 227 (see the report for the actual
 * counts observed).
 */
public class V28__add_show_event_artist_id extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE show_event ADD COLUMN IF NOT EXISTS artist_id bigint "
                    + "REFERENCES artist(id) ON DELETE SET NULL");
            statement.execute(
                    "CREATE INDEX IF NOT EXISTS idx_show_event_artist_id ON show_event (artist_id)");
        }

        backfill(connection);
    }

    /**
     * Exposed as a standalone static method (mirrors {@code V13}'s {@code merge}) so it stays
     * independently callable/testable rather than only reachable through {@link #migrate(Context)}.
     * <p>
     * One row at a time, not batched: at personal-app scale (227 rows total, per this issue's own
     * profile) that is fast enough to not need {@code V19}'s 500-row batching, which existed for a
     * 13,000+ row bulk-import case this migration doesn't have.
     */
    static void backfill(Connection connection) throws Exception {
        try (PreparedStatement read = connection.prepareStatement(
                     "SELECT id, owner, artist_name FROM show_event WHERE artist_id IS NULL");
             PreparedStatement lookup = connection.prepareStatement(
                     "SELECT id FROM artist WHERE owner = ? AND normalized_name = ?");
             PreparedStatement write = connection.prepareStatement(
                     "UPDATE show_event SET artist_id = ? WHERE id = ?");
             ResultSet rows = read.executeQuery()) {
            while (rows.next()) {
                long showId = rows.getLong("id");
                String owner = rows.getString("owner");
                String normalizedName = ArtistNameNormalizer.normalize(rows.getString("artist_name"));

                lookup.setString(1, owner);
                lookup.setString(2, normalizedName);
                try (ResultSet match = lookup.executeQuery()) {
                    if (match.next()) {
                        write.setLong(1, match.getLong("id"));
                        write.setLong(2, showId);
                        write.executeUpdate();
                    }
                }
            }
        }
    }
}
