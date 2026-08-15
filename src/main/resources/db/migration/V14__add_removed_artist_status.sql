-- #117: allow the new ArtistStatus.REMOVED value (an owner taking a hand-curated SEED artist off
-- their list) through the artist_status_check constraint added in V1__baseline.sql.
--
-- DROP CONSTRAINT IF EXISTS, not a bare DROP: V1's CREATE TABLE IF NOT EXISTS is a no-op against
-- a table Flyway adopted rather than created (issue #44's prod crash-loop; see
-- LegacyOwnerColumnMigrationTest), so an artist table that predates V1's formal adoption can
-- reach V14 without artist_status_check ever having been added. A bare DROP CONSTRAINT fails
-- (Postgres 42704) on exactly that legacy shape; IF EXISTS makes this migration work whether or
-- not the constraint was already there, while the ADD below still always leaves it in place.
ALTER TABLE artist DROP CONSTRAINT IF EXISTS artist_status_check;
ALTER TABLE artist ADD CONSTRAINT artist_status_check
    CHECK (status IN ('SEED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'REMOVED'));
