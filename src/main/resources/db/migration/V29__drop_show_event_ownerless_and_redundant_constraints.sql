-- Issue #230: show_event carries THREE unique constraints, not the one the Show entity declares:
--
--   show_event_owner_artist_name_event_date_time_venue_name_key  UNIQUE (owner, artist_name, event_date_time, venue_name)  -- declared, V1
--   ukgwkodx24oenxoi5mv4oqd41v8                                  UNIQUE (owner, artist_name, event_date_time, venue_name)  -- redundant twin
--   uklue4drx2rhjt9e4wsrered7tv                                  UNIQUE (artist_name, event_date_time, venue_name)          -- NO OWNER -- the bug
--
-- Both uk<hash> names are Hibernate ddl-auto=update-era leftovers, never written in any
-- migration -- the same class of bug V22 (#191) found and dropped on `artist`. The owner-less one
-- is the actual production defect: it lets any given concert exist for only ONE owner in the whole
-- system, so a second user (or a shared scan -- whose whole point is surfacing shows two people
-- both follow) discovering an already-stored show cannot save it. `ScanUnitRunner#persistNew`'s
-- per-show loop then throws on that collision, aborting the rest of that scan's batch. Observed for
-- real: the rob+david shared scan's Brandi Carlile job failed against
-- uklue4drx2rhjt9e4wsrered7tv even though the row it collided with belonged to a different owner
-- entirely.
--
-- Two DISTINCT column signatures are in play here, unlike V22's single one:
--   {artist_name, event_date_time, owner, venue_name} -- matches BOTH the declared survivor and its
--     redundant twin, so (as in V22) the survivor must be excluded BY NAME, or a signature-only
--     match could drop either one nondeterministically -- including the one that must stay.
--   {artist_name, event_date_time, venue_name}        -- matches only the owner-less constraint;
--     nothing else shares this signature, so any match here is safe to drop outright.
-- Both are handled in one loop, matched by signature against pg_constraint rather than a
-- hardcoded/guessed name, since the uk<hash> names are environment-specific Hibernate output, not
-- portable facts. The declared name IS a portable fact -- V1__baseline.sql spells it out literally
-- in the CREATE TABLE statement, not left to Postgres's own default-naming convention -- so
-- excluding it by that literal name (V22's exact pattern) is safe on every environment.
--
-- Expected effect on production (read-only profiled 2026-08-21): three UNIQUE constraints exist on
-- show_event -- show_event_owner_artist_name_event_date_time_venue_name_key {artist_name,
-- event_date_time, owner, venue_name}, ukgwkodx24oenxoi5mv4oqd41v8 {artist_name, event_date_time,
-- owner, venue_name} (redundant twin), and uklue4drx2rhjt9e4wsrered7tv {artist_name,
-- event_date_time, venue_name} (owner-less) -- over 228 rows. After this migration: exactly the
-- first remains. show_event's row count is unaffected -- DROP CONSTRAINT touches no rows.
--
-- A database built from V1 onward never has either leftover (V1 creates show_event with only the
-- named composite constraint), so on any such database (fresh dev/test/CI) the loop below finds
-- nothing and this is a safe no-op. It only does real work against a database that carries
-- production's pre-Flyway ddl-auto history.
DO $$
DECLARE
    leftover record;
BEGIN
    FOR leftover IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.contype = 'u'
          AND con.conrelid = 'show_event'::regclass
          AND con.conname <> 'show_event_owner_artist_name_event_date_time_venue_name_key'
          AND (
              SELECT array_agg(attr.attname::text ORDER BY attr.attname)
              FROM pg_attribute attr
              WHERE attr.attrelid = con.conrelid
                AND attr.attnum = ANY (con.conkey)
          ) IN (
              ARRAY['artist_name', 'event_date_time', 'owner', 'venue_name']::text[],
              ARRAY['artist_name', 'event_date_time', 'venue_name']::text[]
          )
    LOOP
        EXECUTE format('ALTER TABLE show_event DROP CONSTRAINT %I', leftover.conname);
    END LOOP;
END $$;
