-- Issue #265: a source failing identically for every artist was indistinguishable from a source
-- that simply found nothing. Bandsintown returned 403 on 2,857 consecutive calls over six hours
-- and every one was recorded as a successful scan, because the adapter swallowed the error into an
-- empty list and an empty list is also what a successful search with no results returns.
--
-- One row per source, global rather than per-owner ON PURPOSE: Bandsintown authenticates with a
-- single shared app_id, so its 403 was every owner's 403 at once. A per-owner table would have
-- needed 403 x owners failures to notice one credential.
CREATE TABLE source_health (
    source               varchar(64)  PRIMARY KEY,
    healthy              boolean      NOT NULL DEFAULT true,

    -- The current unbroken run of failures carrying ONE signature. A dead source repeats the same
    -- signature forever; a source having a bad day produces a mix, and a mix resets the streak.
    consecutive_failures integer      NOT NULL DEFAULT 0,
    streak_signature     varchar(64),
    -- How many DISTINCT artists the streak spans. A source that fails for one artist while
    -- succeeding for others must never flip -- that is the over-eager kill switch this guards
    -- against, and it is why band-site scraping (which fails per-artist by nature) is safe here.
    streak_artists       integer      NOT NULL DEFAULT 0,
    last_artist_id       bigint,

    last_success_at      timestamptz,
    last_failure_at      timestamptz,
    last_error           text,
    unhealthy_since      timestamptz
);
