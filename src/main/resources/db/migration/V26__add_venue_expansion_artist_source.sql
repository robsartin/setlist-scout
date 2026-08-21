-- #206 Task 4: allow the new ArtistSource.VENUE_EXPANSION value (a performer seen on a followed
-- venue's calendar) through the artist_source_check constraint added in V1__baseline.sql.
--
-- DROP CONSTRAINT IF EXISTS, not a bare DROP -- same rationale as
-- V14__add_removed_artist_status.sql, which widened this table's sibling artist_status_check
-- constraint for the same reason: an artist table that predates V1's formal Flyway adoption
-- (issue #44) can reach this migration without artist_source_check ever having been added, and a
-- bare DROP CONSTRAINT fails (Postgres 42704) against that legacy shape.
ALTER TABLE artist DROP CONSTRAINT IF EXISTS artist_source_check;
ALTER TABLE artist ADD CONSTRAINT artist_source_check
    CHECK (source IN ('SEED_LIST', 'MEMBER_EXPANSION', 'SIMILAR_EXPANSION', 'TRIBUTE_EXPANSION', 'VENUE_EXPANSION'));
