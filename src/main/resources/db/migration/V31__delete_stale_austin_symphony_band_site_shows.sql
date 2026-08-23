-- Issue #248: Austin Symphony Orchestra's official_site_url moved from the homepage (host
-- www.austinsymphony.org) to the season page (host austinsymphony.org, no "www") in #218/#245.
-- show_event's unique key is (owner, artist_name, event_date_time, venue_name), so the re-scrape's
-- corrected band-site:austinsymphony.org rows inserted ALONGSIDE the stale
-- band-site:www.austinsymphony.org ones instead of replacing them -- four dates render three
-- times, two of the three copies carrying page furniture the model picked off the old homepage
-- (ASO's own street address, and its own name, both scraped in place of a real venue).
--
-- Scoped by owner + artist_id + the exact stale source string -- a statement about PROVENANCE
-- ("this row came from a site we no longer scrape"), not a guess about which venue name looks
-- wrong. The two source strings differ only by "www.": a LIKE '%austinsymphony%' predicate would
-- catch both the 8 stale rows AND the 15 correct band-site:austinsymphony.org rows alongside them
-- (profiled read-only against production 2026-08-23) -- exact string equality only, never LIKE,
-- for this column.
--
-- Acceptance criteria (profiled read-only against production 2026-08-23, issue #248):
--   show_event total before                                                               : 257
--   owner=rob.sartin@gmail.com, artist_id=37957, source='band-site:www.austinsymphony.org'   : 8 (delete all)
--   owner=rob.sartin@gmail.com, artist_id=37957, source='band-site:austinsymphony.org'        : 15 (must survive)
--   same exact stale source string, ANY other owner/artist                                 : 0 (nothing else to catch)
--   show_event total after                                                                : 249
DELETE FROM show_event
 WHERE owner = 'rob.sartin@gmail.com'
   AND artist_id = 37957
   AND source = 'band-site:www.austinsymphony.org';
