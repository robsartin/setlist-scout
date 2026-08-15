-- Remove backfill (#110) edges that duplicate a live-sourced edge for the same relationship.
-- The V11 backfill ran after the live listener (#109) was already writing edges, and because
-- `source` is part of the unique key, the same relationship got both a 'legacy-unknown' row and a
-- real-adapter row. Keeps every 'legacy-unknown' edge that is a relationship's ONLY record
-- (genuine historical provenance). Verified on prod: deletes 701 of 6,270 rows, leaves 5,569, and
-- leaves ZERO relationships without an edge. The deleted rows are always the older of the pair (by
-- <= ~2h) and carry a byte-identical note, so no provenance is lost.
DELETE FROM artist_edge legacy
 WHERE legacy.source = 'legacy-unknown'
   AND EXISTS (SELECT 1 FROM artist_edge live
                WHERE live.owner           = legacy.owner
                  AND live.from_artist_id  = legacy.from_artist_id
                  AND live.to_artist_id    = legacy.to_artist_id
                  AND live.type            = legacy.type
                  AND live.source <> 'legacy-unknown');
