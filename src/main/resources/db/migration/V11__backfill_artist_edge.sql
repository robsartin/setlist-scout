-- Backfill artist_edge from the pre-graph flat provenance columns on artist (issue #110, epic
-- #108, following the migration plan in docs/explorations/2026-08-14-artist-graph-model.md §4).
-- One edge per existing candidate (discovered_via IS NOT NULL), resolving discovered_via (a name
-- string) back to the base artist's id within the same owner. Idempotent (ON CONFLICT DO NOTHING)
-- and safe to re-run.
--
-- Source-id derivation from the stored source/note, per the live-data profile in #110:
--   MEMBER_EXPANSION            -> 'legacy-unknown'  (MusicBrainz and Discogs write an IDENTICAL
--                                  note, "member/lineup relation of X" -- genuinely indistinguishable
--                                  from stored data alone)
--   TRIBUTE_EXPANSION           -> 'tribute-llm'      (only one tribute adapter has ever existed)
--   SIMILAR_EXPANSION + note LIKE '%(via Last.fm)%' -> 'lastfm'
--   SIMILAR_EXPANSION + note LIKE '%(via LLM)%'     -> 'similar-llm'
--   any other SIMILAR_EXPANSION (legacy wording, e.g. "(single-source match)" /
--     "(confirmed by Last.fm + LLM)") -> 'legacy-unknown'
--
-- Documented gaps (see §4 of the exploration doc -- these are NOT bugs in this migration, they
-- are unrecoverable from data that was never captured):
--   - MusicBrainz vs. Discogs is permanently indistinguishable for MEMBER_EXPANSION rows; both
--     collapse to 'legacy-unknown'.
--   - Corroborating discoveries already lost to the pre-#109 dedup short-circuit in
--     CandidatePersistenceListener (existsByOwnerAndNameIgnoreCase early return) left no trace
--     anywhere and cannot be reconstructed.
--   - Resolution is by EXACT (owner, name) match, consistent with #109's case-sensitive resolve.
--     A discovered_via that doesn't exactly match any current artist.name for that owner (base
--     artist renamed/deleted since) is skipped, not backfilled as a dangling edge.
--
-- Expected live row count (profiled against production ahead of this migration): 4,539 edges
-- total -- 3,542 legacy-unknown / 979 lastfm / 18 tribute-llm / 0 similar-llm -- 0 self-loops,
-- 0 duplicate-key collisions, across 2 owners. SEED_LIST rows (discovered_via IS NULL) correctly
-- produce no edge; they are roots, not discoveries.
INSERT INTO artist_edge (owner, from_artist_id, to_artist_id, type, source, note, weight, created_at)
SELECT a.owner, b.id, a.id, a.source,
       CASE
         WHEN a.source = 'MEMBER_EXPANSION'  THEN 'legacy-unknown'
         WHEN a.source = 'TRIBUTE_EXPANSION' THEN 'tribute-llm'
         WHEN a.note LIKE '%(via Last.fm)%'  THEN 'lastfm'
         WHEN a.note LIKE '%(via LLM)%'      THEN 'similar-llm'
         ELSE 'legacy-unknown'
       END,
       a.note, NULL, a.created_at
  FROM artist a
  JOIN artist b ON b.owner = a.owner AND b.name = a.discovered_via
 WHERE a.discovered_via IS NOT NULL
   AND b.id <> a.id                    -- defensive: never create a self-loop
ON CONFLICT (owner, from_artist_id, to_artist_id, type, source) DO NOTHING;
