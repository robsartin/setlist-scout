-- Widen event_publication.serialized_event beyond VARCHAR(255).
-- Phase B publishes real domain events (e.g. CandidateDiscovered) whose
-- JSON-serialized payload routinely exceeds 255 characters. Spring
-- Modulith's JpaEventPublication entity maps this column as an unbounded
-- String (no @Column(length=...) constraint on serialized_event), so
-- Hibernate's ddl-auto=validate accepts any sufficiently large textual
-- type here -- TEXT is the natural Postgres mapping for that and is what
-- newer Modulith-generated schemas use directly.
ALTER TABLE event_publication
    ALTER COLUMN serialized_event TYPE TEXT;
