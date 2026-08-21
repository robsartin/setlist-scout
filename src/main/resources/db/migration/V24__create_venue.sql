-- #206: a venue whose calendar the owner follows as a show source.
CREATE TABLE venue (
    id              BIGSERIAL PRIMARY KEY,
    owner           VARCHAR(255) NOT NULL,
    name            VARCHAR(255) NOT NULL,
    normalized_name VARCHAR(255) NOT NULL,
    calendar_url    VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- owner first: every lookup is owner-scoped. Mirrors artist's (owner, normalized_name)
-- uniqueness from V21 -- one definition of "same name", app-wide.
CREATE UNIQUE INDEX venue_owner_normalized_name ON venue (owner, normalized_name);
