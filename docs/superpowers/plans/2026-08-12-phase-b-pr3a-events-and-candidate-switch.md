# Phase B — PR3a: domain events + catalog activation service + CandidateDiscovered switch (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development. Steps use `- [ ]` checkboxes.

**Goal:** Light up the first real Spring Modulith application events end-to-end: a catalog **activation service** that owns Artist status transitions and publishes `ArtistActivated`/`ArtistDeactivated`; `SettingsChanged` from settings; and switch expansion's candidate persistence to a durable `CandidateDiscovered` event consumed by a catalog listener. Widen `event_publication.serialized_event` so the registry can hold real payloads. **No job tables yet** (PR3b) — only `CandidateDiscovered` has a consumer; the other three fire but are inert.

**Architecture:** Event records live in a neutral `shared.events` package and carry **primitives only** (no catalog `ArtistSource` enum), so no `catalog↔expansion` cycle. Catalog is the single Artist writer: `ReviewController`'s five inlined transitions + `ArtistSeedService` seed-creation route through `catalog.ArtistActivationService`, which publishes activation events on active-ness changes. Expansion stops persisting candidates directly; it publishes `CandidateDiscovered`; catalog's `@ApplicationModuleListener` persists them (moving the name-guard + owner-dedup into catalog). Behavior is preserved (candidate persistence is equivalent; the old whole-fleet scan still runs).

**Tech Stack:** Java 21, Spring Boot 3.4, Spring Modulith 1.3.12 (`spring-modulith-starter-jpa` already on the classpath → durable JPA event registry), Flyway, JUnit 5 + Mockito + AssertJ, Testcontainers (CI/local-Docker). Build: `JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem ./gradlew …`.

## Global Constraints
- **Behavior preserved.** Same status transitions, same candidate persistence outcome (PENDING_REVIEW with the name-guard + owner-dedup), same review-page flows, same seeding. The old `ShowScanScheduler.scan()` still drives scanning.
- **`ModularityTests` verify() MUST stay green.** Events in `shared.events` (shared is OPEN) carry primitives → no new cross-module type coupling; `catalog`/`expansion`/`settings` depend only on `shared` for events. No cycles.
- **The three activation/settings events are INERT this PR** (no listeners) — that's expected; do not add job listeners (PR3b).
- **`serialized_event` widen is `validate`-sensitive** (Phase-A landmine): the JpaEventPublication entity maps it; a Flyway `V5` must keep `ddl-auto=validate` passing. The hard acceptance gate is `ApplicationContextSmokeTest` (Testcontainers boot) staying green. If widening to a longer type trips validate, match the exact type Hibernate expects (empirically, as in G5) — do NOT change `ddl-auto` or disable validation.
- Keep-green each task: compile + affected tests. Docker up → full `./gradlew build` as CI-parity gate.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`. Branch `86-pr3a-events-and-candidate-switch` (checked out; no worktree).

## Event payload decision (primitives, cycle-safe)
- `ArtistActivated(String owner, Long artistId, String name)`
- `ArtistDeactivated(String owner, Long artistId)`
- `SettingsChanged(String owner)`
- `CandidateDiscovered(String owner, String name, String sourceType, String discoveredVia, String note)`
  — `sourceType` is the `ArtistSource` **enum name** as a String (e.g. `"MEMBER_EXPANSION"`); catalog maps it back with `ArtistSource.valueOf(sourceType)`. This keeps the event free of the catalog enum, so `expansion→shared` and `catalog→shared` are the only edges.

Active-ness rule (used by the activation service): an artist is **active** iff status is `SEED` or `APPROVED`. Publish `ArtistActivated` on a transition (or creation) into active from non-active; `ArtistDeactivated` on active→non-active; nothing otherwise (pending↔rejected, unreject, reject-of-pending).

---

### Task 1: `shared.events` — the four event records
**Files:** Create `src/main/java/com/robsartin/setlistscout/shared/events/ArtistActivated.java`, `ArtistDeactivated.java`, `SettingsChanged.java`, `CandidateDiscovered.java`. Test: `src/test/java/com/robsartin/setlistscout/shared/events/EventRecordsTest.java` (trivial construction/accessor test to satisfy TDD + lock the shapes).

- [ ] **Step 1 (failing test):** assert each record exposes its components, e.g. `new CandidateDiscovered("o","n","MEMBER_EXPANSION","via","note").sourceType()` equals `"MEMBER_EXPANSION"`, and `new ArtistActivated("o", 5L, "Dawes").artistId()` equals `5L`.
- [ ] **Step 2:** run → FAIL (records missing).
- [ ] **Step 3:** create the four `public record`s in package `com.robsartin.setlistscout.shared.events` with the exact components above. Javadoc each: who publishes, who consumes (note the activation/settings ones are inert until PR3b).
- [ ] **Step 4:** run → PASS.
- [ ] **Step 5:** commit `PR3a: add shared.events domain event records`.

---

### Task 2: `catalog.ArtistActivationService` — owns transitions + publishes activation events
**Files:** Create `src/main/java/com/robsartin/setlistscout/catalog/ArtistActivationService.java`. Test: `src/test/java/com/robsartin/setlistscout/catalog/ArtistActivationServiceTest.java`.

**Interfaces / produces:**
- `ArtistActivationService(ArtistRepository, ApplicationEventPublisher)`.
- `void changeStatus(Long id, String owner, ArtistStatus newStatus)` — owner-scoped `findByIdAndOwner`; if absent, no-op. Else capture `old = a.getStatus()`, `a.setStatus(newStatus)`, `save`. Then: if `isActive(newStatus) && !isActive(old)` → `publisher.publishEvent(new ArtistActivated(owner, a.getId(), a.getName()))`; else if `!isActive(newStatus) && isActive(old)` → `publishEvent(new ArtistDeactivated(owner, a.getId()))`. Private `static boolean isActive(ArtistStatus s)` = `s == SEED || s == APPROVED`.
- `void onSeedCreated(Artist saved)` — publishes `new ArtistActivated(saved.getOwner(), saved.getId(), saved.getName())` (a new SEED is active). (Called by ArtistSeedService in Task 3.)

- [ ] **Step 1 (failing test):** unit tests with a mocked `ArtistRepository` + mocked `ApplicationEventPublisher`:
  - PENDING→APPROVED publishes `ArtistActivated(owner, id, name)` (verify captured event).
  - APPROVED→REJECTED (i.e. `remove`) publishes `ArtistDeactivated(owner, id)`.
  - PENDING→REJECTED publishes NOTHING (`verify(publisher, never()).publishEvent(any())`).
  - REJECTED→PENDING (`unreject`) publishes nothing.
  - unknown id → no save, no publish.
  - `onSeedCreated` publishes `ArtistActivated`.
- [ ] **Step 2:** run → FAIL.
- [ ] **Step 3:** implement as specified.
- [ ] **Step 4:** run → PASS.
- [ ] **Step 5:** commit `PR3a: add catalog ArtistActivationService (status changes publish activation events)`.

---

### Task 3: route ReviewController + ArtistSeedService through the activation service
**Files:** Modify `review/ReviewController.java`, `catalog/ArtistSeedService.java`, and their tests (`review/ReviewControllerTest.java`, and any ArtistSeedService test if present — check `catalog/ArtistSeedServiceTest.java`).

**Changes (behavior identical, now event-emitting):**
- `ReviewController`: inject `ArtistActivationService` instead of using `ArtistRepository` for writes. Replace the inline `a.setStatus(...); artistRepository.save(a)` in `review` (accept→APPROVED, reject→REJECTED), `approveAllPending` (→APPROVED), `rejectAllPending` (→REJECTED), and the `setStatus` helper used by `unreject`/`remove`, with `activationService.changeStatus(a.getId(), owner, newStatus)`. Keep the read (`findByOwnerAndStatus`) via the repository (or a catalog read method). `expandNow` unchanged. The `review` batch iterates pending and calls `changeStatus` per accept/reject.
- `ArtistSeedService.addSeedIfNew`: after saving a new SEED artist, call `activationService.onSeedCreated(saved)` (inject the service). Keep its trim/blank/#-comment/dedupe logic. **Watch the dependency direction:** `ArtistActivationService` and `ArtistSeedService` are both in `catalog` (same module — fine, no cross-module concern). Avoid a constructor cycle (ActivationService doesn't depend on SeedService, so this is a simple one-way dep).
- Tests: update `ReviewControllerTest` to construct with a mocked `ArtistActivationService` and assert it's called with the right (id/owner/status) per action (the existing status-outcome assertions move to ArtistActivationServiceTest; here assert delegation). Update the seed test to verify `onSeedCreated` fires on a new seed.

- [ ] Step 1: rewrite the affected tests (failing). Step 2: run → FAIL. Step 3: implement. Step 4: run → PASS (`review.*` + `catalog.*`). Step 5: commit `PR3a: route status transitions + seed creation through ArtistActivationService`.

---

### Task 4: `SettingsService` publishes `SettingsChanged`
**Files:** Modify `settings/SettingsService.java` + `settings/SettingsServiceTest.java`.

- Inject `ApplicationEventPublisher`. In `updateSettings(owner, …)`, after the save, `publishEvent(new SettingsChanged(owner))`. `getOrCreateSettings` does NOT publish (creation/default, not a user change). Behavior otherwise identical.
- [ ] Step 1: failing test — `updateSettings` publishes `SettingsChanged(owner)` (mocked publisher); `getOrCreateSettings` publishes nothing. Step 2: FAIL. Step 3: implement. Step 4: PASS. Step 5: commit `PR3a: settings publishes SettingsChanged on update`.

---

### Task 5: expansion publishes `CandidateDiscovered`; catalog listener persists it
**Files:** Modify `expansion/ExpansionService.java` (+ its test); Create `catalog/CandidatePersistenceListener.java` (+ test `catalog/CandidatePersistenceListenerTest.java`).

**Expansion side:** replace `saveIfNew(owner, name, source, discoveredVia, note)` with publishing `new CandidateDiscovered(owner, name, source.name(), discoveredVia, note)` for each discovered name (drop the name-guard + dedup + `artistRepository.save` from expansion — those move to catalog). ExpansionService keeps `artistRepository` ONLY for the base-artist read (`findByOwnerAndStatusIn`); inject `ApplicationEventPublisher`. Remove `looksLikeArtistName`/`MAX_NAME_*` from expansion (they move to the listener). The three `expand…` methods now count "published" instead of "saved" (the info log wording may change to "candidatesPublished" — acceptable, non-behavioral). Keep the `safely` per-dimension isolation, the member-union/similar-confirmed-by-both/tribute-SEED-only logic, and every note string.

**Catalog side — `CandidatePersistenceListener`:** an `@org.springframework.modulith.events.ApplicationModuleListener` method `on(CandidateDiscovered e)` that reproduces the old `saveIfNew`: skip blank; apply the name-plausibility guard (`looksLikeArtistName`, moved here with `MAX_NAME_LENGTH=60`/`MAX_NAME_WORDS=8`); `if (artistRepository.existsByOwnerAndNameIgnoreCase(e.owner(), e.name())) return;`; else persist `Artist(e.name(), ArtistSource.valueOf(e.sourceType()), ArtistStatus.PENDING_REVIEW, e.discoveredVia(), e.note())` with `setOwner(e.owner())` + save. Dep: `ArtistRepository`.

- [ ] Step 1: failing tests —
  - `ExpansionServiceTest` (rewrite): with a mocked `ApplicationEventPublisher`, assert `expandAll` publishes `CandidateDiscovered` with the right `(name, sourceType=source.name(), discoveredVia, note)` for member/similar(confirmed & single)/tribute cases, the SEED-only tribute rule, and per-dimension isolation — mirror the current assertions but on published events instead of saved artists. (The guard/dedup tests move to the listener test.)
  - `CandidatePersistenceListenerTest`: unit-call `on(new CandidateDiscovered(...))` with a mocked repo → asserts the guard rejects prose/blank, dedup skips existing, and a good candidate is saved as PENDING_REVIEW with the mapped `ArtistSource` + note.
- [ ] Step 2: FAIL. Step 3: implement both. Step 4: PASS (`expansion.*` + `catalog.*`). Step 5: commit `PR3a: expansion publishes CandidateDiscovered; catalog listener persists candidates`.

Note on `@ApplicationModuleListener`: it is async + transactional + runs after the publisher's transaction commits, backed by the durable registry. The unit test calls the listener method directly (synchronous) — that's fine for the persistence logic. End-to-end async delivery is covered by Task 7's integration test.

---

### Task 6: widen `event_publication.serialized_event` (Flyway V5)
**Files:** Create `src/main/resources/db/migration/V5__widen_event_publication_serialized_event.sql`.

- Real `CandidateDiscovered` payloads (JSON) will exceed `VARCHAR(255)`. Widen `serialized_event` (and consider `event_type`/`listener_id` if needed) to a longer type. **Determine the exact type empirically** so `ddl-auto=validate` still passes: apply the migration, run `ApplicationContextSmokeTest` (Testcontainers boot → Flyway V1..V5 → Hibernate validate); if validate complains, match the type Hibernate expects for the entity's mapping (the same method that produced V4 in G5 — boot once with `ddl-auto=update` against a scratch Postgres, read the generated column type, use it). Likely `TEXT` or an unbounded varchar. Do NOT alter `ddl-auto` or enable Modulith schema-init.
- [ ] Step 1: write V5. Step 2: run `ApplicationContextSmokeTest` → it MUST pass (this is the gate). Step 3: iterate the type if validate fails. Step 4: commit `PR3a: widen event_publication.serialized_event for real event payloads (Flyway V5)`.

---

### Task 7: integration test + verify() + full-suite gate
**Files:** Create `src/test/java/com/robsartin/setlistscout/CandidateDiscoveredFlowTest.java` (or extend an existing `@ApplicationModuleTest`).

- Use Spring Modulith's `@ApplicationModuleTest` + the `Scenario` API (Testcontainers Postgres) to assert: **publishing `CandidateDiscovered` results in a PENDING_REVIEW Artist persisted by the catalog listener** (guarded + deduped), exercising the real async+durable path. This is the one end-to-end event test; the per-unit logic is already unit-tested (Tasks 2/4/5). If the Modulith `Scenario`/`@ApplicationModuleTest` API proves fiddly, fall back to a `@SpringBootTest` that publishes the event via `ApplicationEventPublisher` and awaits the persisted Artist (await with a bounded poll, not a fixed sleep).
- [ ] Step 1: write the failing integration test. Step 2: run it (Docker up) → drive to green. Step 3: **gate:** `./gradlew test --tests "…ModularityTests"` green, then `./gradlew --no-daemon clean build` BUILD SUCCESSFUL (all tests incl. the boot/validate test + this new event test). Step 4: commit `PR3a: end-to-end CandidateDiscovered event test` (only if it added a file; the gate itself is verification).

---

## Self-Review
- **Spec coverage:** PR3a = the events + activation service + CandidateDiscovered switch + widen, per the split agreed 2026-08-12. Job tables + activation/settings listeners = PR3b; poller/cutover = PR4. ✅
- **Cycle safety:** events in `shared.events` with primitive payloads; `sourceType` as a String avoids the catalog enum crossing into expansion's published type. verify() gate in Task 7.
- **Behavior:** status transitions, candidate persistence (guard+dedup, now in catalog), seeding, settings update all preserved; only internal log wording and the persistence *path* change.
- **Empirical risks flagged:** the `serialized_event` widen (validate) and the Modulith async event test both carry acceptance-gated, iterate-if-needed steps rather than assumed-exact code.
- **Placeholder check:** event shapes, activation-service logic, the switch, and the listener are concrete; the two Modulith-specific spots (widen type, `@ApplicationModuleTest` API) are acceptance-driven by necessity (version-specific empirical behavior), with a named fallback.
