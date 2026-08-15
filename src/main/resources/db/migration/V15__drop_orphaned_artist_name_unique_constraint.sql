-- Issue #141: drop the orphaned, pre-multi-tenancy UNIQUE(name) constraint on `artist`.
--
-- Production's `artist` table predates the multi-tenant `owner` column (#27, see
-- V2__reconcile_owner_columns.sql) and still carries a single-column `UNIQUE (name)` constraint
-- from that single-tenant era, auto-named by Hibernate's legacy ddl-auto=update (a `uk<hash>`
-- name, e.g. `ukr03a96lqhsb7djq2tn4rq60vn` -- environment-specific, never written in any
-- migration). V1's `CREATE TABLE IF NOT EXISTS` is a no-op against that already-existing table,
-- and V2's retrofit only looks for a constraint literally named `artist_owner_name_key`, so
-- neither one ever found or dropped this orphaned one.
--
-- Because `ArtistRepository.insertIfAbsent`'s `ON CONFLICT (owner, name) DO NOTHING` only
-- suppresses conflicts on the constraint it names, a violation of this OTHER, still-present
-- constraint keeps throwing -- hit repeatedly in production whenever two different owners add an
-- artist with the same name, a case the composite (owner, name) constraint is supposed to allow
-- (this app is multi-tenant by owner; cross-owner name collisions are expected, not an error).
--
-- Looks the constraint up by column signature -- any UNIQUE constraint on `artist` whose column
-- set is EXACTLY {name} -- instead of a hardcoded/guessed name, since the Hibernate-generated hash
-- is per-environment and not a portable fact. This can never match the composite
-- `artist_owner_name_key` constraint (column set {name, owner}), so that one is always left alone.
-- A database built from V1 onward never has this constraint at all -- V1 creates `artist` with
-- only the composite constraint whenever the table doesn't already exist -- so on any such
-- database (fresh dev/test/CI) the loop below simply finds nothing and this is a safe no-op. It
-- only does real work against a legacy database that predates V1's formal adoption.
--
-- DROP CONSTRAINT, not DROP COLUMN: no data loss, no rows affected.
DO $$
DECLARE
    orphaned record;
BEGIN
    FOR orphaned IN
        SELECT con.conname
        FROM pg_constraint con
        WHERE con.contype = 'u'
          AND con.conrelid = 'artist'::regclass
          AND (
              SELECT array_agg(attr.attname::text ORDER BY attr.attname)
              FROM pg_attribute attr
              WHERE attr.attrelid = con.conrelid
                AND attr.attnum = ANY (con.conkey)
          ) = ARRAY['name']::text[]
    LOOP
        EXECUTE format('ALTER TABLE artist DROP CONSTRAINT %I', orphaned.conname);
    END LOOP;
END $$;
