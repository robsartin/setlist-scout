package db.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import db.migration.support.DuplicateArtistMerger;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Issue #266: {@link ArtistNameNormalizer} treated a hyphen and a space as different characters, so
 * {@code Yo-Yo Ma} and {@code Yo Yo Ma} were two artists and {@code UNIQUE (owner, normalized_name)}
 * admitted both. #261 folded the invisible {@code U+2010}/{@code U+2011} characters; this is the
 * remaining, wider case.
 *
 * <p>Profiled read-only against production 2026-09-10: <b>18 duplicate pairs</b>, every one the same
 * act — hyphenated surnames ({@code Fairweather-Low}, {@code Cephas-Jones}), romanised given names
 * ({@code Hae-Young}, {@code Si-Jing}), and band-name variants ({@code E Street}/{@code E-Street},
 * {@code All Starr}/{@code All-Starr}, {@code Yo-Yo Ma}). Zero false positives in the whole catalog.
 * Six of those rows were active and being scanned — three artists scanned twice each.
 *
 * <h2>Order matters</h2>
 * Merge first, then re-backfill. Recomputing {@code normalized_name} with the corrected normalizer
 * first would make both rows of every pair compute the same value and violate the unique constraint
 * before anything merged. Keyed off {@link DuplicateArtistMerger.GroupKey#NORMALIZE_NAME_IN_JAVA},
 * which recomputes from {@code artist.name} through the fixed normalizer -- exactly the grouping the
 * constraint enforces a moment later. The STORED column would group by the old folding and find
 * nothing.
 *
 * <h2>Survivor policy</h2>
 * {@link DuplicateArtistMerger.SurvivorPolicy#PREFER_ACTIVE}, as V33 used, for the same reason: two
 * of these pairs have one row rejected and one active ({@code Jean-Michel Jarre} [SEED] /
 * {@code Jean Michel Jarre} [REJECTED], and {@code Jasmine Cephas Jones} [SEED] /
 * {@code Jasmine Cephas-Jones} [REJECTED]). The owner rejected one spelling with no reasonable way
 * to know it duplicated one they had already approved; honouring that rejection would drop both
 * artists off the active list.
 *
 * <h2>Acceptance criteria (production, 2026-09-10)</h2>
 * <pre>
 *   duplicate pairs collapsed                      : 18
 *   artist rows deleted                            : 18
 *   active rows that were duplicates               : 6 rows -> 3 artists
 *   pairs with one rejected and one active         : 2
 *   Yo-Yo Ma, Jean-Michel Jarre,
 *   Jasmine Cephas Jones, Pousette-Dart Band       : survive active
 * </pre>
 */
public class V34__fold_hyphen_as_space_and_merge_duplicates extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        DuplicateArtistMerger.merge(connection,
                DuplicateArtistMerger.GroupKey.NORMALIZE_NAME_IN_JAVA,
                DuplicateArtistMerger.SurvivorPolicy.PREFER_ACTIVE);

        // Re-normalize EVERY row, not only merged ones: any name containing a hyphen has a stale
        // normalized_name even with no duplicate, and that column is what ArtistNameMatcher's
        // indexed lookup and #250's search compare against.
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
