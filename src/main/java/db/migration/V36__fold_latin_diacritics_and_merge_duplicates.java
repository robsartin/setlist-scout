package db.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import db.migration.support.DuplicateArtistMerger;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Issue #268: {@link ArtistNameNormalizer} preserved Latin diacritics, so {@code Céline Dion} and
 * {@code Celine Dion} were two artists and {@code UNIQUE (owner, normalized_name)} admitted both.
 * It also meant {@code /artists?q=beyonce} found nothing: anyone without an accented keyboard could
 * not reach {@code Beyoncé} at all.
 *
 * <p>Profiled against all 38,666 production rows, 2026-09-10, keyed exactly as this migration keys
 * (so measured AFTER V34 and V35 have collapsed their own groups): <b>44 groups, 44 rows deleted</b>
 * -- 8 with both rows active and therefore being scanned twice, 4 mixing an inactive row with an
 * active one, 32 with both rows inactive.
 *
 * <h2>Survivor policy</h2>
 * {@link DuplicateArtistMerger.SurvivorPolicy#PREFER_ACTIVE}, on the same reasoning V35 established
 * and for a case that is, if anything, clearer: all 4 mixed groups are one artist under two
 * spellings ({@code Oumou Sangaré}/{@code Oumou Sangare},
 * {@code Øystein Sevåg}/{@code Oystein Sevâg}), with the active side approved or hand-seeded. The
 * rejection is a judgement about the duplicate, not about the artist.
 *
 * <h2>Order matters</h2>
 * Merge first, then re-backfill -- the reverse makes both rows of every pair compute the same value
 * and violates the unique constraint before anything merges. Keyed off
 * {@link DuplicateArtistMerger.GroupKey#NORMALIZE_NAME_IN_JAVA}, which recomputes through the fixed
 * normalizer; the stored column would group by the old rule and find nothing.
 *
 * <h2>What this migration must NOT do</h2>
 * The fold is confined to Latin script, and the re-backfill below rewrites every row -- so if that
 * confinement were ever broken, this migration is what would write the damage to disk. The first
 * implementation profiled for #268 dropped every {@code NON_SPACING_MARK} unconditionally, which
 * strips the Japanese dakuten and would have rewritten {@code ミドリ} to {@code ミトリ} across 9 rows.
 * {@code StripLeadingArticleMigrationTest}'s sibling here asserts that a Japanese, Korean, Hebrew
 * and CJK row come through byte-identical; see {@link ArtistNameNormalizer} for the full argument.
 *
 * <h2>Acceptance criteria (production, 2026-09-10)</h2>
 * <pre>
 *   groups collapsed                             : 44
 *   artist rows deleted                          : 44
 *   groups with two or more active rows          : 8   (were being scanned twice)
 *   groups mixing an inactive and an active row  : 4   (PREFER_ACTIVE keeps the active one)
 *   non-Latin rows whose key changes             : 0
 *   names normalizing to empty                   : 0
 *   /artists?q=beyonce finds Beyoncé             : yes
 * </pre>
 */
public class V36__fold_latin_diacritics_and_merge_duplicates extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        DuplicateArtistMerger.merge(connection,
                DuplicateArtistMerger.GroupKey.NORMALIZE_NAME_IN_JAVA,
                DuplicateArtistMerger.SurvivorPolicy.PREFER_ACTIVE);

        // Re-normalize EVERY row: an accented name has a stale normalized_name even with no
        // duplicate, and that column is what ArtistNameMatcher's indexed lookup and #250's search
        // compare against -- the search fix IS this backfill.
        try (PreparedStatement read = connection.prepareStatement("SELECT id, name FROM artist");
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
    }
}
