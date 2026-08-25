package db.migration;

import com.robsartin.setlistscout.catalog.ArtistNameNormalizer;
import db.migration.support.DuplicateArtistMerger;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

/**
 * Issue #261: {@link ArtistNameNormalizer} did not fold {@code U+2010 HYPHEN} or
 * {@code U+2011 NON-BREAKING HYPHEN}, only the four dashes (en, em, horizontal bar, minus). Those
 * two render identically to a plain {@code -}, so two spellings of the same act never collapsed to
 * one key and {@code UNIQUE (owner, normalized_name)} (#179) admitted both as separate artists.
 *
 * <p>Profiled read-only against production 2026-08-24: <b>33 duplicate pairs, 66 rows</b>, ten of
 * them with both rows active and therefore scanned every cycle. Examples:
 * {@code Drive-By Truckers}/{@code Drive‐By Truckers} (both APPROVED),
 * {@code Blue Note All-Stars}/{@code Blue Note All‐Stars} (APPROVED/REJECTED).
 *
 * <h2>Order matters</h2>
 * Merge first, backfill second. Recomputing {@code normalized_name} with the corrected normalizer
 * before merging would make both rows of every pair compute the SAME value and immediately violate
 * {@code UNIQUE (owner, normalized_name)} -- the migration would fail and the app would not boot.
 * So the merge is keyed off {@link DuplicateArtistMerger.GroupKey#NORMALIZE_NAME_IN_JAVA}, which
 * recomputes from {@code artist.name} through the fixed normalizer, exactly the grouping the
 * constraint will enforce a moment later. Keying off the STORED column instead would group by the
 * OLD folding and find nothing to merge.
 *
 * <h2>Survivor policy</h2>
 * {@link DuplicateArtistMerger.SurvivorPolicy#PREFER_ACTIVE}, the inverse of what V13/V21 used.
 * The owner rejected one row of these pairs with no way to know it duplicated one they had already
 * approved -- U+2010 and U+2011 are invisible next to a hyphen, so the app rendered them as two
 * unrelated acts. Honouring that rejection would silently drop {@code Blue Note All-Stars} and
 * {@code P‐Floyd} off the active list and demote a hand-added {@code Yo‑Yo Ma & Kathryn Stott}
 * seed. Only 4 of the 33 pairs are mixed-status, so this choice moves exactly those four.
 *
 * <h2>Acceptance criteria (production, 2026-08-24)</h2>
 * <pre>
 *   duplicate pairs collapsed                     : 33
 *   artist rows deleted                           : 33  (23 REJECTED, 10 APPROVED, 0 SEED)
 *   show_event rows affected                      : 0   -- nothing loses a show
 *   artist_edge rows repointed or collapsed       : 222
 *   scan_job / expand_job rows on losers          : 20 / 40
 *   pairs where BOTH rows were active             : 10
 *   Blue Note All-Stars, P-Floyd                  : survive APPROVED
 *   Yo-Yo Ma &amp; Kathryn Stott, Pousette-Dart Band : survive SEED
 * </pre>
 */
public class V33__fold_unicode_hyphens_and_merge_duplicates extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();

        DuplicateArtistMerger.merge(connection,
                DuplicateArtistMerger.GroupKey.NORMALIZE_NAME_IN_JAVA,
                DuplicateArtistMerger.SurvivorPolicy.PREFER_ACTIVE);

        // Re-normalize EVERY row, not just the merged ones: any name carrying U+2010/U+2011 has a
        // stale normalized_name even when it never had a duplicate, and leaving it stale would put
        // the stored column out of step with what the app now computes -- which is what
        // ArtistNameMatcher's indexed lookup compares against.
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
