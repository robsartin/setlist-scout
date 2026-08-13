-- Optimistic-lock version columns so a poller's reschedule can't silently clobber a concurrent
-- SettingsChanged re-due (or an ArtistDeactivated delete). Existing rows start at 0.
ALTER TABLE scan_job   ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE expand_job ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
