-- #177: one row per name from a bulk artist upload, drained by a claim-lease poller.
-- The upload endpoint used to call addSeedIfNew per line inside the HTTP request; a real
-- 1,138-name file returned a 502 and was killed part-way by the free tier's idle spin-down
-- after importing 79 names. Queueing lets the request return immediately.
--
-- Deliberately NOT modelled on scan_job/expand_job's shared AbstractJob mapping: that requires a
-- non-null artist_id, and an import row has no artist yet -- creating one is the whole point.
CREATE TABLE IF NOT EXISTS artist_import (
    id              bigserial PRIMARY KEY,
    owner           varchar(255) NOT NULL,
    name            varchar(255) NOT NULL,
    normalized_name varchar(255) NOT NULL,
    status          varchar(32)  NOT NULL,
    attempts        integer      NOT NULL DEFAULT 0,
    last_error      text,
    claimed_at      timestamp(6) with time zone,
    next_due_at     timestamp(6) with time zone NOT NULL,
    created_at      timestamp(6) with time zone NOT NULL
);

-- Idempotent re-upload, enforced by the database rather than a read-then-write check:
-- a name already queued for this owner cannot be queued twice. PARTIAL, on PENDING only --
-- a DONE row must not block re-importing a name the owner later removed and wants back.
CREATE UNIQUE INDEX IF NOT EXISTS artist_import_pending_key
    ON artist_import (owner, normalized_name) WHERE status = 'PENDING';

-- The poller's claim query orders by next_due_at over due rows.
CREATE INDEX IF NOT EXISTS idx_artist_import_due
    ON artist_import (next_due_at) WHERE status = 'PENDING';
