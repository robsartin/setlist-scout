-- Issue #255: the 2026-08-18 SEED_LIST upload carried 14 lines that are fragments of artist names
-- split on their commas -- "Crosby, Stills, Nash & Young" arriving as "Crosby" / "Stills" /
-- "Nash & Young", "Peter, Paul and Mary" as "Peter" / "Paul & Mary", "Emerson, Lake & Palmer" as
-- "Emerson" / "Lake & Palmer", and "Leslie Odom, Jr." as "Leslie Odom" / "Jr.".
--
-- The damage arrived in the uploaded file: ArtistImportService.queue reads one name per line and
-- never splits on commas (checked before writing this). So this is a data fix, not a parser fix.
--
-- Each fragment resolved to a real but WRONG artist and was then expanded from -- "Tim" to a Korean
-- ballad singer, "Robin" to a Finnish pop singer, "Jr." to a bachata artist, "Crosby" to a dancehall
-- artist, "Peter" to Peter Gabriel, "Guitar" to Johnny "Guitar" Watson -- producing 119 children
-- between them. 118 of those 119 are already REJECTED, so this migration deliberately does NOT
-- cascade: it stops the 14 roots being scanned and leaves the descendants where they are.
--
-- REMOVED, not DELETE, on purpose. artist_edge.from_artist_id/to_artist_id are NO ACTION foreign
-- keys, so deleting these rows would force deleting 247 provenance edges with them -- discarding
-- the record of how the junk spread, which is the evidence for #254. REMOVED is outside
-- ArtistActivationService's active set (SEED/APPROVED), so the rows leave the artists list and stop
-- being scanned, and the change is reversible. Because a migration cannot go through
-- ArtistActivationService, the ArtistDeactivated event that would normally cancel the scan jobs
-- never fires -- so this deletes those jobs explicitly. That is the whole reason the second
-- statement exists; without it the poller keeps scanning 14 names nobody wants.
--
-- Acceptance criteria (profiled read-only against production 2026-08-23, issue #255):
--   artist total before                                             : 35623   (unchanged -- no deletes)
--   matching rows (owner, SEED_LIST, SEED, the 14 names)            : 14      (all -> REMOVED)
--   the same 14 names under any OTHER owner                         : 0       (nothing else to catch)
--   scan_job total before                                           : 7041
--   scan_job rows for those 14 artists                              : 28      (delete all)
--   scan_job total after                                            : 7013
--   show_event rows referencing those 14 artists                    : 0       (nothing loses a show)
--   artist_edge rows referencing them                               : 247     (all preserved)
--   'The Johnny Watson Trio' (APPROVED, the one non-rejected child) : untouched
--   'Peter, Paul and Mary'   (APPROVED, via Mary Travers)           : untouched
--
-- Scoped by owner AND source AND status AND exact name equality -- never LIKE. "Peter" as a LIKE
-- pattern would match "Peter Gabriel", "Peter Frampton", "Peter Rowan", "Pete Seeger"'s neighbours
-- and "Peter, Paul and Mary" itself.
UPDATE artist
   SET status = 'REMOVED'
 WHERE owner = 'rob.sartin@gmail.com'
   AND source = 'SEED_LIST'
   AND status = 'SEED'
   AND name IN ('Peter', 'Paul & Mary', 'Crosby', 'Stills', 'Nash & Young', 'Emerson',
                'Lake & Palmer', 'Leslie Odom', 'Jr.', 'Tim', 'Robin', 'Guitar', 'LED', 'Mundi');

DELETE FROM scan_job
 WHERE owner = 'rob.sartin@gmail.com'
   AND artist_id IN (
       SELECT id FROM artist
        WHERE owner = 'rob.sartin@gmail.com'
          AND source = 'SEED_LIST'
          AND status = 'REMOVED'
          AND name IN ('Peter', 'Paul & Mary', 'Crosby', 'Stills', 'Nash & Young', 'Emerson',
                       'Lake & Palmer', 'Leslie Odom', 'Jr.', 'Tim', 'Robin', 'Guitar', 'LED', 'Mundi'));
