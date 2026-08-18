-- Issue #191: drop the redundant, ddl-auto-era duplicate of `artist_owner_name_key`.
--
-- Production's `artist` table carries a SECOND `UNIQUE (owner, name)` constraint alongside the
-- one the migrations declare (`artist_owner_name_key`, from V1__baseline.sql, retrofitted for
-- legacy databases by V2__reconcile_owner_columns.sql). Its name is Hibernate's legacy
-- ddl-auto=update signature -- a `uk<hash>` name, e.g. `ukpn9qra77pidiwnm8vj55pl6bt` --
-- environment-specific and never written in any migration, from before ADR-0009 moved the schema
-- to Flyway.
--
-- It is harmless today: both constraints cover the exact same column set, so anything that
-- violates one violates the other. But it is invisible in the migration history -- the same class
-- of bug V15 (#141) fixed for the single-column orphan -- and since #179 moved
-- `ArtistRepository.insertIfAbsent`'s `ON CONFLICT` target to `(owner, normalized_name)`, neither
-- remaining `(owner, name)` constraint is the arbiter of inserts any more. A future change to the
-- declared constraint would leave this invisible twin still silently enforcing the old rule,
-- unnoticed because `ON CONFLICT` only suppresses violations of the constraint it names.
--
-- Looks the constraint up by column signature -- any UNIQUE constraint on `artist` whose column
-- set is EXACTLY {name, owner} -- instead of a hardcoded/guessed name, since the Hibernate-
-- generated hash is per-environment and not a portable fact. UNLIKE V15 (whose target column set
-- {name} could never match the composite constraint), this signature matches BOTH the redundant
-- constraint AND `artist_owner_name_key` itself, so the declared name is explicitly excluded from
-- the loop. Dropping `artist_owner_name_key` would be a real regression: `ddl-auto: validate` and
-- the `Artist` entity's `@UniqueConstraint(columnNames = {"owner", "name"})` both expect it to
-- still exist.
--
-- A database built from V1 onward never has the redundant constraint -- V1 creates `artist` with
-- only the named composite constraint -- so on any such database (fresh dev/test/CI) the loop
-- below excludes the one match by name, finds nothing else, and this is a safe no-op. It only
-- does real work against a database that predates V1's formal adoption and still carries the
-- ddl-auto leftover.
--
-- Expected effect on production (read-only profiled 2026-08-18): three UNIQUE constraints exist
-- on `artist` -- `artist_owner_name_key` {name, owner}, `artist_owner_normalized_name_key`
-- {normalized_name, owner}, and the redundant `ukpn9qra77pidiwnm8vj55pl6bt` {name, owner}. After
-- this migration: exactly the first two remain. `artist` row count is unaffected -- 21,579 before
-- and after -- since DROP CONSTRAINT touches no rows.
--
-- DROP CONSTRAINT, not DROP COLUMN: no data loss, no rows affected.
DO $$
DECLARE
    redundant record;
BEGIN
    FOR redundant IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.contype = 'u'
          AND con.conrelid = 'artist'::regclass
          AND con.conname <> 'artist_owner_name_key'
          AND (
              SELECT array_agg(attr.attname::text ORDER BY attr.attname)
              FROM pg_attribute attr
              WHERE attr.attrelid = con.conrelid
                AND attr.attnum = ANY (con.conkey)
          ) = ARRAY['name', 'owner']::text[]
    LOOP
        EXECUTE format('ALTER TABLE artist DROP CONSTRAINT %I', redundant.conname);
    END LOOP;
END $$;
