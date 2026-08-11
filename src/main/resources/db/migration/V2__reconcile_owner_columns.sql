-- Reconcile a legacy, already-populated database up to the V1 baseline (see ADR-0009, issue #44).
--
-- Prod's tables predate multi-tenancy (#27): they hold rows but have NO `owner` column, and V1's
-- `CREATE TABLE IF NOT EXISTS` left them untouched when Flyway adopted the DB (#30). Hibernate
-- `ddl-auto=validate` then crash-looped on the missing column. This migration is idempotent: a
-- no-op on a fresh DB (V1 already created every column and constraint) and a full retrofit on a
-- legacy DB. Every column is added with IF NOT EXISTS; the feature columns that arrived after the
-- original schema (owner, ZIP-geocode lat/long, band-site URL, etc.) are all covered so validate
-- passes in a single deploy rather than failing one missing column at a time.
--
-- Nullable columns are added bare. `owner` is added nullable, backfilled for the pre-existing
-- single-user rows, then set NOT NULL. Owner-scoped unique constraints are (re)created only when
-- absent, so this stays a no-op where V1 already added them.
--
-- The backfill value is the original sole user (matches setlistscout.auth.seed-owner, already the
-- committed default in application.yml). It is never used on a fresh DB -- there are no rows.

-- === artist ===
ALTER TABLE artist ADD COLUMN IF NOT EXISTS owner varchar(255);
ALTER TABLE artist ADD COLUMN IF NOT EXISTS discovered_via varchar(255);
ALTER TABLE artist ADD COLUMN IF NOT EXISTS note varchar(255);
ALTER TABLE artist ADD COLUMN IF NOT EXISTS official_site_url varchar(255);
UPDATE artist SET owner = 'rob.sartin@gmail.com' WHERE owner IS NULL;
ALTER TABLE artist ALTER COLUMN owner SET NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'artist_owner_name_key') THEN
        ALTER TABLE artist ADD CONSTRAINT artist_owner_name_key UNIQUE (owner, name);
    END IF;
END $$;

-- === search_settings ===
ALTER TABLE search_settings ADD COLUMN IF NOT EXISTS owner varchar(255);
ALTER TABLE search_settings ADD COLUMN IF NOT EXISTS postal_code varchar(255);
ALTER TABLE search_settings ADD COLUMN IF NOT EXISTS city varchar(255);
ALTER TABLE search_settings ADD COLUMN IF NOT EXISTS state varchar(255);
ALTER TABLE search_settings ADD COLUMN IF NOT EXISTS latitude double precision;
ALTER TABLE search_settings ADD COLUMN IF NOT EXISTS longitude double precision;
UPDATE search_settings SET owner = 'rob.sartin@gmail.com' WHERE owner IS NULL;
ALTER TABLE search_settings ALTER COLUMN owner SET NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'search_settings_owner_key') THEN
        ALTER TABLE search_settings ADD CONSTRAINT search_settings_owner_key UNIQUE (owner);
    END IF;
END $$;

-- === show_event ===
ALTER TABLE show_event ADD COLUMN IF NOT EXISTS owner varchar(255);
ALTER TABLE show_event ADD COLUMN IF NOT EXISTS venue_city varchar(255);
ALTER TABLE show_event ADD COLUMN IF NOT EXISTS price numeric(38, 2);
ALTER TABLE show_event ADD COLUMN IF NOT EXISTS ticket_url varchar(255);
UPDATE show_event SET owner = 'rob.sartin@gmail.com' WHERE owner IS NULL;
ALTER TABLE show_event ALTER COLUMN owner SET NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'show_event_owner_artist_name_event_date_time_venue_name_key') THEN
        ALTER TABLE show_event ADD CONSTRAINT show_event_owner_artist_name_event_date_time_venue_name_key
            UNIQUE (owner, artist_name, event_date_time, venue_name);
    END IF;
END $$;
