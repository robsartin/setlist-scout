-- #206: durable per-venue scan job with a claim lease, mirroring scan_job's shape without
-- extending shared.AbstractJob -- that requires a non-null artist_id, which a venue job has no
-- value for (same call catalog.ArtistImport made in #177 for artist_import).
CREATE TABLE venue_scan_job (
    id           BIGSERIAL PRIMARY KEY,
    owner        VARCHAR(255) NOT NULL,
    venue_id     BIGINT NOT NULL REFERENCES venue (id) ON DELETE CASCADE,
    status       VARCHAR(255) NOT NULL,
    attempts     INTEGER NOT NULL DEFAULT 0,
    last_error   TEXT,
    claimed_at   TIMESTAMP(6) WITH TIME ZONE,
    next_due_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    last_run_at  TIMESTAMP(6) WITH TIME ZONE,
    created_at   TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- ON DELETE CASCADE: removing a venue must not strand its jobs. The claim query orders by
-- next_due_at, so index it alongside status.
CREATE INDEX venue_scan_job_due ON venue_scan_job (status, next_due_at);
CREATE UNIQUE INDEX venue_scan_job_venue ON venue_scan_job (owner, venue_id);
