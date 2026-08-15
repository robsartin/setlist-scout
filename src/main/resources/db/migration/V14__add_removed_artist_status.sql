-- #117: allow the new ArtistStatus.REMOVED value (an owner taking a hand-curated SEED artist off
-- their list) through the artist_status_check constraint added in V1__baseline.sql.
ALTER TABLE artist DROP CONSTRAINT artist_status_check;
ALTER TABLE artist ADD CONSTRAINT artist_status_check
    CHECK (status IN ('SEED', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'REMOVED'));
