# Artist import: store the normalized name, and queue the work

**Status:** design, awaiting review.
**Origin:** a 1,138-artist Apple Music export needs importing, which exposed two defects in the existing bulk-upload path.

## The measurement that started this

`ArtistController#upload` reads a text file and calls `ArtistSeedService#addSeedIfNew` per line, synchronously, inside the HTTP request. Each of those calls reaches:

```java
public Optional<ArtistNameStatusView> findExistingMatch(String owner, String candidateName) {
    String target = ArtistNameNormalizer.normalize(candidateName);
    return artistRepository.findByOwner(owner).stream()          // ALL rows, no status filter
            .filter(view -> ArtistNameNormalizer.normalize(view.getName()).equals(target))
            .findFirst();
}
```

`findByOwner` is unfiltered. `rob.sartin@gmail.com` currently holds **12,862** artist rows. So every line of the file loads all 12,862 and re-normalizes each one in Java.

For a 1,138-name file: roughly **14.6 million row-loads and normalizations**, across 1,138 separate transactions, in one request. Plus the event fan-out — each newly-active artist publishes `ArtistActivated`, which three listeners turn into ~8 job rows, so about **9,000 further inserts**. Order of minutes on a developer machine; well past any gateway timeout on Render's 0.1-CPU free tier.

It also degrades **quadratically**: cost is O(lines × total artists), so importing 1,138 artists roughly doubles the catalog and makes the *next* import twice as expensive per line.

## Two separable defects

1. **The request does unbounded work synchronously.** It should accept, queue, and return.
2. **The matching is algorithmically wrong** — a membership test that scans and re-normalizes the whole catalog per candidate. This sits on the path for *every* add, not just uploads.

Fixing only (1) relocates a multi-minute operation to the background, where it still hammers the database and still degrades. Fixing only (2) might make uploads fast enough to survive synchronously, but leaves no progress reporting and keeps the 9,000-insert fan-out inside the request. Both are in scope, as two independently shippable pieces.

---

## Part A — store `normalized_name`

Stop recomputing what should be persisted.

### Schema

A **Java-based** Flyway migration, following the precedent of `V13__merge_duplicate_variant_artists`:

1. Add `normalized_name varchar(255)` to `artist`, nullable at first.
2. Backfill it by calling `ArtistNameNormalizer.normalize` — **not** a SQL approximation. That class is the single definition of "same name" (CLAUDE.md), and it folds unicode dashes, curly quotes, and whitespace-around-hyphens that a regex in SQL would miss. V13 already established doing this in Java for exactly this reason.
3. Merge any collisions the backfill finds, reusing V13's merge behaviour.
4. Set `NOT NULL`.
5. Add `UNIQUE (owner, normalized_name)`.

### The unique constraint is viable — measured, not assumed

A production scan (approximating the normalizer in SQL: case-fold, whitespace collapse, hyphen-spacing) found **exactly one** colliding group across 12,862 rows:

```
Paul Quinichette - John Coltrane Quintet
Paul Quinichette-John Coltrane Quintet
```

A genuine duplicate, from a row created before #157 taught the normalizer to fold whitespace around hyphens. The SQL approximation does *not* cover unicode dash or curly-quote folding, so the real backfill may surface one or two more — which is why step 3 merges rather than assumes zero.

**If the migration cannot resolve a collision it must fail loudly, not skip.** `ddl-auto: validate` plus a failed migration means the app refuses to boot, which is the correct outcome for an unresolvable duplicate — far better than silently dropping one.

### Keeping it in sync — the part that will silently break

`ArtistRepository#insertIfAbsent` is a **native query**. JPA lifecycle callbacks do not fire for it. A `@PrePersist` that populates `normalized_name` would cover ordinary `save()` calls and silently miss the native insert path, leaving nulls that either fail the `NOT NULL` or defeat the unique index.

**Both write paths must populate it explicitly.** Any implementation that relies on a lifecycle callback alone is wrong, and a test must cover the `insertIfAbsent` path specifically.

### What this buys

- `findExistingMatch` becomes a single indexed lookup: `findByOwnerAndNormalizedName(owner, normalized)`.
- Every add gets faster, not just uploads.
- **The #118 duplicate guard moves into the database.** Today it is a read-then-write that can race (#133 documents the race and the `ON CONFLICT` compensation). With a unique constraint, `addSeedIfNew` can drop the pre-check and rely on `ON CONFLICT (owner, normalized_name) DO NOTHING` — the race stops being possible rather than being handled after the fact.

That last point is the reason this part is worth doing on its own merits, independent of the import.

---

## Part B — queue the import

### Flow

1. `POST /artists/upload` reads the file, trims, drops blanks and `#` comments, and **dedupes within the file** by normalized form.
2. It bulk-inserts one `artist_import` row per surviving name, status `PENDING`.
3. It returns immediately: *"Queued 1,138 names."*
4. A claim-lease poller, mirroring `ScanPoller`, claims due rows with `SKIP LOCKED`, calls `addSeedIfNew` per name, and marks each `DONE` or `FAILED`.

### Table

`artist_import`: `id`, `owner`, `name`, `normalized_name`, `status` (`PENDING`/`DONE`/`FAILED`), `attempts`, `last_error`, `claimed_at`, `next_due_at`, `created_at`.

Deliberately **not** reusing `AbstractJob`: that mapped superclass requires a non-null `artist_id`, and an import row has no artist yet — that is the whole point of it. It mirrors the shape without inheriting a column it cannot satisfy.

### One row per name, not per file

Chosen over a single batch row with a cursor. It gives per-name retry and backoff for free from the existing poller pattern, exact progress, and failure isolation — one malformed name cannot fail the batch. ~1,138 rows is trivial beside the 6,044 expand jobs already live.

### Decisions taken (flag if wrong)

- **Re-uploading the same file is idempotent.** Names already `PENDING` for that owner are skipped at queue time, so a double upload does not queue 2,276 rows. `DONE` rows do not block re-queueing — re-importing a file after removing an artist should work.
- **Failed names stay visible.** `FAILED` rows are retained with their `last_error` and surfaced on the Artists page as a count with the names reachable. With 1,138 at a time, a silent drop is how you end up wondering months later why an artist never appeared.
- **The poller is paced**, consistent with the app's others — but without external-API backoff, since this work is database-only. Its own config value, not a shared one.

### Progress

While any row is `PENDING` for the owner, the Artists page shows a plain count — *"Importing: 847 remaining."* No polling machinery, no JavaScript; it renders on whatever page load or htmx swap happens next.

---

## Non-goals

No CSV/JSON parsing — plain text, one name per line, as today. No import history or audit view beyond pending/failed counts. No undo. No change to what `addSeedIfNew` *means* (it still reactivates a matching inactive artist to `SEED`). No change to expansion behaviour: imported seeds expand exactly like any other seed, which is the intended behaviour.

## Sequencing

**Part A ships first and independently.** It is valuable alone — every add gets faster and the duplicate guard becomes enforceable — and it removes the reason Part B's background work would otherwise still be slow.

## Testing

**Part A**
- The backfill produces the same value as `ArtistNameNormalizer.normalize` for a row containing an en dash, a curly apostrophe, and whitespace around a hyphen.
- The `insertIfAbsent` native path populates `normalized_name` — the specific case a lifecycle callback would miss.
- Two case-variant names for one owner cannot both exist: the unique constraint rejects the second.
- The same name under two different owners is still allowed.
- `findExistingMatch` returns the same answers as before for the case, punctuation, and unicode cases already covered by `ArtistNameMatcherTest` — behaviour preserved, mechanism changed.

**Part B**
- Upload returns before processing completes, and the queued count is correct.
- Duplicates within one file are queued once.
- Re-uploading the same file while rows are still `PENDING` queues nothing new.
- A name that fails all retries lands `FAILED` with its error, and does not block the rest of the file.
- Owner-scoping: an import queued by one owner never creates artists for another.
- The pending count reaches zero and the expected artists exist.
