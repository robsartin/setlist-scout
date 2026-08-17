package db.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Issue #176: stores {@link ArtistNameNormalizer#normalize(String)}'s output in
 * {@code artist.normalized_name} so name matching becomes an indexed lookup instead of a
 * full-catalog scan.
 *
 * <p>Before this, {@code ArtistNameMatcher#findExistingMatch} loaded EVERY artist row for the
 * owner and re-normalized each one in Java, per candidate name -- 13,236 rows for the main user.
 * A 1,138-name bulk import was therefore ~15 million row-loads and normalizations; it timed out
 * with a 502 in production and was killed part-way by the free tier's idle spin-down.
 *
 * <h2>Why a Java migration, not SQL</h2>
 * Same reason as {@code V13__merge_duplicate_variant_artists}: {@link ArtistNameNormalizer} folds
 * unicode dashes and curly quotes with explicit character replacements and deliberately preserves
 * non-ASCII text. Reproducing that in SQL would be a second, hand-rolled definition of "same name"
 * that could silently drift from the Java one -- the exact drift that inflated #118's first
 * live-profiling pass from 3 real pairs to a false 13.
 *
 * <h2>Deliberately NOT unique</h2>
 * A {@code UNIQUE (owner, normalized_name)} constraint would require merging pre-existing
 * collisions, and merging correctly means repointing {@code artist_edge}/{@code scan_job}/{@code
 * expand_job} references -- which is what V13 already does in careful detail. That is a follow-up
 * issue, not this one. A plain index delivers the entire performance fix, and
 * {@code findExistingMatch} keeps its existing "first match wins" semantics, which stays correct
 * with duplicates present.
 */
public class V19__add_artist_normalized_name extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE artist ADD COLUMN IF NOT EXISTS normalized_name varchar(255)");
        }

        // Backfill in one pass. Read id+name, write normalized_name -- batched so a large catalog
        // does not build one enormous statement.
        try (PreparedStatement read = connection.prepareStatement(
                     "SELECT id, name FROM artist WHERE normalized_name IS NULL");
             PreparedStatement write = connection.prepareStatement(
                     "UPDATE artist SET normalized_name = ? WHERE id = ?");
             ResultSet rows = read.executeQuery()) {
            int batched = 0;
            while (rows.next()) {
                write.setString(1, ArtistNameNormalizer.normalize(rows.getString("name")));
                write.setLong(2, rows.getLong("id"));
                write.addBatch();
                if (++batched % 500 == 0) {
                    write.executeBatch();
                }
            }
            write.executeBatch();
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE artist ALTER COLUMN normalized_name SET NOT NULL");
            // Not unique -- see the class javadoc. Owner first: every lookup is owner-scoped.
            statement.execute("CREATE INDEX IF NOT EXISTS idx_artist_owner_normalized_name "
                    + "ON artist (owner, normalized_name)");
        }
    }
}
