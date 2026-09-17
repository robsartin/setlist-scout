-- Issue #284 (Films 1/3). Two columns, each load-bearing for a different half of the sub-project.
--
-- venue.kind -- LIVE or CINEMA. Set EXPLICITLY on the add form, never inferred from the URL or the
-- scraped content: this one field drives three separate behaviours (the extraction prompt, whether
-- VenuePerformerSeen is published, and whether a screening is visible), and a wrong inference
-- silently files hundreds of film titles as PENDING_REVIEW artists -- the same pollution class as
-- #253's LLM prose and #255's comma-split seed fragments.
--
-- DEFAULT 'LIVE' rather than a nullable column: every existing row is a live venue (there is one in
-- production), and every code path that reads kind must get an answer rather than branching on null.
-- The CHECK mirrors artist_status_check/artist_source_check (V1/V14) and show_event's own kind check
-- (V23) -- the enum's spelling is enforced by the database, not only by @Enumerated(STRING).
ALTER TABLE venue ADD COLUMN IF NOT EXISTS kind varchar(255) NOT NULL DEFAULT 'LIVE';

ALTER TABLE venue DROP CONSTRAINT IF EXISTS venue_kind_check;
ALTER TABLE venue ADD CONSTRAINT venue_kind_check CHECK (kind IN ('LIVE', 'CINEMA'));

-- show_event.release_year -- a film's year, nullable because it is meaningless for a concert.
--
-- Captured NOW rather than when sub-project 2 needs it, for a reason that does not survive delay: a
-- film's identity is title+year (repertory cinemas screen Dune (1984) and Dune (2021) in the same
-- season), and UNIQUE (owner, normalized_name) cannot express that. Backfilling later would mean
-- re-scraping calendars whose screenings have already passed and are gone from the page.
--
-- Deliberately NOT part of the natural key. Two screenings of one film differ by showtime, which
-- event_date_time already carries; adding the year to the key would let a mis-extracted year
-- duplicate a screening rather than collide with it.
ALTER TABLE show_event ADD COLUMN IF NOT EXISTS release_year integer;

-- show_event.kind gains FILM. Without this the Java enum and the database disagree, and the
-- disagreement is INVISIBLE: VenueScanRunner#run catches RuntimeException and records a job
-- failure, so every screening insert violating the V23 CHECK looks exactly like a venue that
-- published nothing. That is not hypothetical -- it is how this migration's absence first showed
-- up, as a #284 test passing for the wrong reason.
ALTER TABLE show_event DROP CONSTRAINT IF EXISTS show_event_kind_check;
ALTER TABLE show_event ADD CONSTRAINT show_event_kind_check CHECK (kind IN ('MUSIC', 'COMEDY', 'FILM'));
