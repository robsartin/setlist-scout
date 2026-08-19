-- Issue #202: comedy shows alongside music. TicketmasterService now reads Ticketmaster's own
-- per-event classification (segment/genre/subGenre -- previously fetched in every response and
-- discarded) instead of assuming every result is music, so each show needs somewhere to record
-- which one it is. Bandsintown (music-only) and the band-site scraper (music-oriented) have no
-- classification signal of their own, so they set MUSIC explicitly at their Show construction
-- site -- a true label for what those sources are, not an inferred one.
--
-- NOT NULL, not nullable-as-unknown: every code path that builds a Show now supplies a kind (see
-- above), so no row is ever created without one, and a nullable column would only invite "did
-- someone forget to set it" doubt for zero benefit. Added nullable first, backfilled, THEN set
-- NOT NULL -- the standard-safe order for adding a required column to a populated table (an
-- ALTER ... NOT NULL in the same step as the ADD COLUMN would fail outright on any existing row).
--
-- Backfilled 'music', not left null: show_event holds ~42 rows per #202's own scoping comment --
-- not independently re-profiled here, since it doesn't change the conclusion either way. Every
-- row that exists was necessarily found under the old classificationName=music-only filter (this
-- app has never supported comedy before now), so 'music' is not a guess for any of them, it's
-- what already searched and matched. Cheap and accurate for a table this small.
--
-- CHECK constraint mirrors the artist_status_check/artist_source_check pattern from V1/V14: kind
-- is a true fixed enum (Show.Kind), not an open string like show_event.source, so it gets the
-- same belt-and-suspenders the other enum-backed columns have.
ALTER TABLE show_event ADD COLUMN IF NOT EXISTS kind varchar(255);
UPDATE show_event SET kind = 'MUSIC' WHERE kind IS NULL;
ALTER TABLE show_event ALTER COLUMN kind SET NOT NULL;
ALTER TABLE show_event ADD CONSTRAINT show_event_kind_check CHECK (kind IN ('MUSIC', 'COMEDY'));
