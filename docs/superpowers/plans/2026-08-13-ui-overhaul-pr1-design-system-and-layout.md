# UI overhaul PR1 — design system + shared layout + top nav Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Establish setlist-scout's themeable CSS foundation (auto light/dark design system + shared Thymeleaf layout + persistent top nav) and migrate the existing pages onto it — a pure visual change with **no routing or behavior change**. (PR1 of 2 for #96; PR2 does the page split + grouped candidates.)

**Architecture:** A single stylesheet `static/css/app.css` holds a CSS-custom-property token system (light on bare `:root`, dark via `@media (prefers-color-scheme: dark)` — the app auto-follows the browser, **no manual toggle**). A reusable Thymeleaf fragment `templates/fragments/layout.html` provides the shared `<head>` (the `app.css` link + vendored htmx) and the top nav; each page composes it and supplies its own content fragment. `shows.html` and `artists.html` migrate onto the layout + tokens, dropping their inline `<style>` blocks. htmx fragment responses still resolve their inner named fragments (no layout wrapper), so all existing htmx behavior is unchanged.

**Tech Stack:** Spring Boot 3 + Spring Modulith, Thymeleaf, vendored htmx (no CDN), CSS custom properties, JUnit 5 + Testcontainers + MockMvc.

## Global Constraints

- **JDK 21:** `export JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem` before any gradle command. Run gradle in the FOREGROUND (blocking).
- **No new runtime dependencies; no external CDN/CSS/JS.** htmx is already vendored at `static/js/htmx.min.js`. No webfonts — system-font stack only.
- **Server-rendered Thymeleaf + htmx, no SPA.** Preserve ALL existing behavior (seeding, upload, site-url, remove, unreject, review radios, approve-all/reject-all, expand-now, scan-now, settings, sorting) and **owner isolation**.
- **Auto light/dark only:** `app.css` uses ONLY bare `:root` (light) + `@media (prefers-color-scheme: dark) :root { … }`. **No `[data-theme]` blocks** — that was a mockup-only preview affordance; the shipped app has no toggle.
- **WCAG AA both themes:** ≥4.5:1 for body text, ≥3:1 for large text / UI borders; visible `:focus-visible` on every interactive element; `@media (prefers-reduced-motion: reduce)` disables transitions.
- **Modulith stays green** (`ModularityTests`). Templates + `static/` are cross-cutting resources, not a module — no boundary impact. No controller/Java package moves in PR1.
- **Full gate before PR:** `./gradlew --no-daemon build` BUILD SUCCESSFUL + `python3 scripts/check_adrs.py` exit 0. Docker up for Testcontainers. Harmless Hikari/`eventPublicationRegistry` shutdown WARNs are expected.
- **Branch:** `96-ui-overhaul` (already created off `main`; the design spec `docs/superpowers/specs/2026-08-13-ui-overhaul-design.md` is committed here). Never commit to `main`.
- **Token values are fixed** (from the approved mockup) — Task 1 lists them verbatim; do not invent colors.

---

## Task 1: `app.css` — the design-system stylesheet

**Files:**
- Create: `src/main/resources/static/css/app.css`
- Test: `src/test/java/com/robsartin/setlistscout/web/AppCssServedTest.java`

**Interfaces:**
- Produces: a served static stylesheet at `/css/app.css` defining the token system + base component classes (`.topbar`, `nav.main`, `.wrap`, `.card`/`section.card`, `table`, `.chip.member|.similar|.tribute`, `button`/`.btn`/`.btn-primary`/`.btn-good`/`.btn-bad`/`.btn-sm`, `.note`, `.eyebrow`, `.count`, form inputs, `:focus-visible`). Later tasks/PR2 style through these tokens/classes.

- [ ] **Step 1: Write the failing served-resource test** — `AppCssServedTest.java`

```java
package com.robsartin.setlistscout.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.hamcrest.Matchers.containsString;

// Reuse the project's Testcontainers Postgres setup exactly as ArtistPageRenderTest does
// (copy its @SpringBootTest + @Testcontainers + @DynamicPropertySource header).
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class AppCssServedTest {

    @Autowired WebApplicationContext context;
    MockMvc mvc;

    @org.junit.jupiter.api.BeforeEach
    void setup() { mvc = MockMvcBuilders.webAppContextSetup(context).build(); }

    @Test
    void appCssIsServedWithBothThemes() throws Exception {
        mvc.perform(get("/css/app.css"))
           .andExpect(status().isOk())
           .andExpect(content().contentTypeCompatibleWith("text/css"))
           .andExpect(content().string(containsString(":root")))
           .andExpect(content().string(containsString("prefers-color-scheme: dark")))
           .andExpect(content().string(containsString("--primary")))
           .andExpect(content().string(containsString("--tribute-ink")));
    }
}
```

Copy the exact Testcontainers header (container field, `@DynamicPropertySource`) from `src/test/java/com/robsartin/setlistscout/web/ArtistPageRenderTest.java` so the context boots against real Postgres. `addFilters=false` isn't needed here (no auth on a static resource), but match the class's boot setup.

- [ ] **Step 2: Run it — expect FAIL** (no `app.css` yet → 404)

```bash
export JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.AppCssServedTest" --console=plain
```

- [ ] **Step 3: Create `app.css`** with the token system (verbatim values from the approved mockup) + base components. **Only `:root` + the dark media query — no `[data-theme]`.**

```css
/* ===== Setlist Scout design system ===== */
/* Light palette (bare :root). Dark auto-applies from the browser; no manual toggle. */
:root {
  --bg:#f6f5fb; --surface:#ffffff; --surface-2:#f0eef8;
  --text:#1b1930; --muted:#605b76; --border:#e4e1f0; --border-strong:#d3cfe6;
  --primary:#5a4fd0; --primary-hover:#4a40bb; --on-primary:#ffffff; --primary-soft:#ece9fb;
  --member-bg:#d5f0e9; --member-ink:#0e6b58;
  --similar-bg:#dbe6ff; --similar-ink:#1c419c;
  --tribute-bg:#ece0ff; --tribute-ink:#6a29d3;
  --good:#157a4e; --good-bg:#e2f3ea; --bad:#b42a30; --bad-bg:#fbe7e7;
  --shadow:0 1px 2px rgba(27,25,48,.05), 0 6px 20px rgba(27,25,48,.06);
  --radius:12px;
  --font:system-ui,-apple-system,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
}
@media (prefers-color-scheme: dark) {
  :root {
    --bg:#131120; --surface:#1d1a2e; --surface-2:#262238;
    --text:#ece9f7; --muted:#a49ec0; --border:#2f2b45; --border-strong:#3c3757;
    --primary:#a99bff; --primary-hover:#b9adff; --on-primary:#161226; --primary-soft:#241f3b;
    --member-bg:#123a33; --member-ink:#7fe0cb;
    --similar-bg:#1b2b52; --similar-ink:#a6c4ff;
    --tribute-bg:#2c2246; --tribute-ink:#cab4ff;
    --good:#7bd3a3; --good-bg:#132e22; --bad:#f0a1a3; --bad-bg:#3a1e21;
    --shadow:0 1px 2px rgba(0,0,0,.3), 0 8px 24px rgba(0,0,0,.35);
  }
}
* { box-sizing:border-box; }
body { margin:0; background:var(--bg); color:var(--text); font-family:var(--font); line-height:1.5; -webkit-font-smoothing:antialiased; }
.wrap { max-width:940px; margin:0 auto; padding:0 1.25rem 4rem; }

/* Top nav */
header.topbar { position:sticky; top:0; z-index:5; background:var(--surface); border-bottom:1px solid var(--border); }
.topbar-inner { max-width:940px; margin:0 auto; padding:.7rem 1.25rem; display:flex; align-items:center; gap:1.25rem; flex-wrap:wrap; }
.brand { display:flex; align-items:center; gap:.55rem; font-weight:700; letter-spacing:-.01em; }
.brand .dot { width:12px; height:12px; border-radius:50%; background:var(--primary); box-shadow:0 0 0 4px var(--primary-soft); }
nav.main { display:flex; gap:.25rem; flex-wrap:wrap; }
nav.main a { text-decoration:none; color:var(--muted); font-weight:550; font-size:.93rem; padding:.38rem .7rem; border-radius:8px; }
nav.main a:hover { color:var(--text); background:var(--surface-2); }
nav.main a[aria-current="page"] { color:var(--primary); background:var(--primary-soft); }

/* Typography helpers */
h1 { font-size:1.5rem; letter-spacing:-.02em; margin:2rem 0 .3rem; text-wrap:balance; }
h2 { font-size:1.06rem; letter-spacing:-.01em; margin:0 0 .4rem; }
.page-sub { color:var(--muted); margin:0 0 1.4rem; font-size:.95rem; }
.eyebrow { text-transform:uppercase; letter-spacing:.08em; font-size:.72rem; font-weight:700; color:var(--muted); }
.note { color:var(--muted); font-size:.88rem; }
.count { font-variant-numeric:tabular-nums; color:var(--muted); font-weight:600; }

/* Surfaces + tables */
section.card, .card { background:var(--surface); border:1px solid var(--border); border-radius:var(--radius); box-shadow:var(--shadow); padding:1.15rem 1.25rem; margin-bottom:1.1rem; }
table { border-collapse:collapse; width:100%; }
th,td { text-align:left; padding:.6rem .7rem; border-bottom:1px solid var(--border); vertical-align:middle; }
th { font-size:.74rem; text-transform:uppercase; letter-spacing:.05em; color:var(--muted); font-weight:700; }
th a { color:inherit; text-decoration:none; }
th a:hover { text-decoration:underline; }
tbody tr:last-child td { border-bottom:0; }

/* Chips */
.chip { display:inline-block; padding:.12rem .5rem; border-radius:999px; font-size:.72rem; font-weight:700; white-space:nowrap; }
.chip.member { background:var(--member-bg); color:var(--member-ink); }
.chip.similar { background:var(--similar-bg); color:var(--similar-ink); }
.chip.tribute { background:var(--tribute-bg); color:var(--tribute-ink); }

/* Buttons */
button, .btn { font:inherit; font-weight:600; cursor:pointer; border-radius:8px; padding:.4rem .8rem; border:1px solid var(--border-strong); background:var(--surface-2); color:var(--text); font-size:.9rem; }
button:hover, .btn:hover { border-color:var(--primary); }
button:focus-visible, a:focus-visible, input:focus-visible, summary:focus-visible { outline:2px solid var(--primary); outline-offset:2px; }
.btn-primary { background:var(--primary); color:var(--on-primary); border-color:var(--primary); }
.btn-primary:hover { background:var(--primary-hover); border-color:var(--primary-hover); }
.btn-good { color:var(--good); background:var(--good-bg); border-color:transparent; }
.btn-bad { color:var(--bad); background:var(--bad-bg); border-color:transparent; }
.btn-sm { padding:.26rem .6rem; font-size:.82rem; }

/* Forms */
input[type=text], input[type=url], input[type=number], input[type=file] { font:inherit; padding:.4rem .6rem; border-radius:8px; border:1px solid var(--border-strong); background:var(--surface); color:var(--text); }
form.inline { display:inline; }
.settings { background:var(--surface-2); padding:1rem; border-radius:var(--radius); border:1px solid var(--border); }
.notice { color:var(--good); font-weight:600; margin-top:1rem; }
.visually-hidden { position:absolute; width:1px; height:1px; padding:0; margin:-1px; overflow:hidden; clip:rect(0 0 0 0); white-space:nowrap; border:0; }
.htmx-swapping { opacity:.4; transition:opacity .2s; }
@media (prefers-reduced-motion: reduce) { * { transition:none !important; } }
```

(You may add a class the existing templates need that isn't listed — but every color MUST come from a token, never a literal, and no color may be defined only inside the media block.)

- [ ] **Step 4: Run the test — expect PASS.**

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/static/css/app.css src/test/java/com/robsartin/setlistscout/web/AppCssServedTest.java
git commit -m "#96 PR1: app.css design system (auto light/dark tokens + base components)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: shared layout fragment + migrate `shows.html`

**Files:**
- Create: `src/main/resources/templates/fragments/layout.html`
- Modify: `src/main/resources/templates/shows.html`
- Test: `src/test/java/com/robsartin/setlistscout/web/ShowsPageRenderTest.java` (create)

**Interfaces:**
- Consumes: `/css/app.css` (Task 1).
- Produces: `fragments/layout.html :: page(title, active, content)` — renders `<head>` (charset, viewport, `<title>`, `app.css` link, vendored htmx script) + the top nav (Shows | Artists | Settings, `aria-current="page"` on the one matching `active`) + a `<main class="wrap">` wrapping the passed content fragment. `active` values used so far: `'shows'`, `'artists'`. (PR2 adds `'candidates'`, `'rejected'` nav items.)

- [ ] **Step 1: Write the failing `ShowsPageRenderTest`** (copy the Testcontainers + MockMvc + `oidcLogin` + `addFilters` setup from `ArtistPageRenderTest`; use an owner NOT in `seed-bands.txt`)

```java
@Test
void showsPageRendersWithNavAndStylesheet() throws Exception {
    mvc.perform(get("/").with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
       .andExpect(status().isOk())
       .andExpect(content().string(containsString("/css/app.css")))
       .andExpect(content().string(containsString("aria-current=\"page\"")))   // Shows is current
       .andExpect(content().string(containsString(">Artists<")))               // nav link present
       .andExpect(content().string(containsString("id=\"shows-region\"")))     // htmx region preserved
       .andExpect(content().string(containsString("/settings")));              // settings form preserved
}

@Test
void scanNowHtmxReturnsBareShowsRegionFragment() throws Exception {
    var res = mvc.perform(post("/scan-now").header("HX-Request", "true")
                    .with(csrf())   // include only if the project uses CSRF; match ArtistPageRenderTest
                    .with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
       .andExpect(status().isOk())
       .andReturn().getResponse().getContentAsString();
    // htmx fragment must be JUST the region — no full-page chrome:
    org.assertj.core.api.Assertions.assertThat(res).contains("shows-region");
    org.assertj.core.api.Assertions.assertThat(res).doesNotContain("<head").doesNotContain("topbar");
}
```

Match `ArtistPageRenderTest`'s exact auth/CSRF/token approach. `OWNER` = a fresh email not in `seed-bands.txt`.

- [ ] **Step 2: Run it — expect FAIL** (no nav/app.css yet in shows; the fragment test may already pass or fail depending on current markup — the first test fails on the missing nav/css link).

- [ ] **Step 3: Create `fragments/layout.html`**

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" th:fragment="page(title, active, content)">
<head>
    <meta charset="UTF-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <title th:text="${title}">Setlist Scout</title>
    <link rel="stylesheet" th:href="@{/css/app.css}"/>
    <script th:src="@{/js/htmx.min.js}"></script>
</head>
<body>
<header class="topbar">
    <div class="topbar-inner">
        <span class="brand"><span class="dot" aria-hidden="true"></span> Setlist Scout</span>
        <nav class="main" aria-label="Primary">
            <a th:href="@{/}" th:attrappend="aria-current=${active == 'shows'} ? 'page'">Shows</a>
            <a th:href="@{/artists}" th:attrappend="aria-current=${active == 'artists'} ? 'page'">Artists</a>
            <a th:href="@{/#settings}" th:attrappend="aria-current=${active == 'settings'} ? 'page'">Settings</a>
        </nav>
    </div>
</header>
<main class="wrap">
    <div th:replace="${content}">content</div>
</main>
</body>
</html>
```

Note: `th:attrappend="aria-current=…"` renders `aria-current="page"` only on the active link (an unmatched ternary yields no attribute). If your Thymeleaf version renders an empty attribute, use `th:attr="aria-current=${active=='shows'} ? 'page' : null"` instead — pick whichever produces the attribute ONLY on the active item (the `AppCss`/render test asserts exactly one `aria-current="page"`).

- [ ] **Step 4: Migrate `shows.html`** to the layout. Replace the whole file with the content-fragment form; drop the inline `<style>`; keep the `showsRegion` fragment (htmx target for `ShowController` returning `"shows :: showsRegion"`), the settings form (add `id="settings"` to the `.settings` div so the nav anchor lands), the `scanQueued` notice, the sort links, and the tribute chip (now `class="chip tribute"`).

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org"
      th:replace="~{fragments/layout :: page('Upcoming Shows', 'shows', ~{::content})}">
<body>
<div th:fragment="content">
    <h1>Upcoming Shows</h1>

    <div class="settings" id="settings">
        <form th:action="@{/settings}" method="post"> … existing fields (unchanged), Save … </form>
        <form class="inline" th:action="@{/scan-now}" method="post"
              hx-post="/scan-now" hx-target="#shows-region" hx-swap="outerHTML">
            <button type="submit">Scan now</button>
        </form>
    </div>

    <div th:fragment="showsRegion" id="shows-region" aria-live="polite">
        … existing scanQueued notice + shows table + empty-state, chips → class="chip tribute" …
    </div>
</div>
</body>
</html>
```

Keep every `th:*` binding and htmx attribute exactly as they are today — only the outer skeleton (now the layout), the class names (tokens), and the removed `<style>` change. Wrap the shows table in a `<div style="overflow-x:auto">` (or a `.table-scroll` class you add to app.css) so it scrolls on narrow screens instead of the body.

- [ ] **Step 5: Run the tests — expect PASS.** Confirm both `ShowsPageRenderTest` cases pass (full page has nav + css; htmx fragment is bare).

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/templates/fragments/layout.html src/main/resources/templates/shows.html \
        src/test/java/com/robsartin/setlistscout/web/ShowsPageRenderTest.java
git commit -m "#96 PR1: shared layout fragment + top nav; migrate shows.html onto it

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: migrate `artists.html` onto the layout + tokens

**Files:**
- Modify: `src/main/resources/templates/artists.html`
- Test: `src/test/java/com/robsartin/setlistscout/web/ArtistPageRenderTest.java` (extend)

**Interfaces:**
- Consumes: `fragments/layout.html :: page(...)` with `active='artists'`.
- Produces: no new interface — same page, same `activeSection`/`pendingSection` fragments (htmx targets), same routes.

- [ ] **Step 1: Extend `ArtistPageRenderTest`** — add assertions to its existing GET `/artists` render test (keep all current assertions + owner-isolation):

```java
// on the existing GET /artists render:
.andExpect(content().string(containsString("/css/app.css")))
.andExpect(content().string(containsString("aria-current=\"page\"")))   // Artists is current
.andExpect(content().string(containsString(">Shows<")))                 // nav present
.andExpect(content().string(containsString("id=\"active-section\"")))   // fragments preserved
.andExpect(content().string(containsString("id=\"pending-section\"")))
```

And add (or confirm) a case that an htmx action returns the bare fragment:

```java
@Test
void approveAllHtmxReturnsBarePendingSection() throws Exception {
    // seed a PENDING_REVIEW artist for OWNER first (via the repo/service the class already uses)
    var res = mvc.perform(post("/artists/approve-all-pending").header("HX-Request","true")
                    .with(csrf()).with(oidcLogin().idToken(t -> t.claim("email", OWNER))))
       .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    org.assertj.core.api.Assertions.assertThat(res).contains("pending-section");
    org.assertj.core.api.Assertions.assertThat(res).doesNotContain("<head").doesNotContain("topbar");
}
```

Match the class's existing fixture style for creating the pending artist and its auth/CSRF approach.

- [ ] **Step 2: Run it — expect FAIL** (artists.html not migrated yet → no `/css/app.css`, no `aria-current`).

- [ ] **Step 3: Migrate `artists.html`** onto the layout (same transform as shows.html): outer `th:replace="~{fragments/layout :: page('Artists', 'artists', ~{::content})}"`, wrap the body in `<div th:fragment="content">`, drop the inline `<style>`, keep BOTH named fragments (`activeSection` id `active-section`, `pendingSection` id `pending-section`) and every htmx attribute / `th:*` binding, the log-level form, upload form, review radios, and the rejected table. Map the inline classes to tokens: `.tag.tag-tribute`→`chip tribute`, `.tag.tag-member`→`chip member`, `.tag.tag-similar`→`chip similar`; wrap the section headings in `section.card` or keep `<h1>`/tables as-is but styled by app.css; approve/reject buttons may take `.btn-good`/`.btn-bad`. Wrap each `<table>` in an `overflow-x:auto` container. Do NOT change any route, form action, fragment name, or `th:*` logic.

- [ ] **Step 4: Run the full render test class — expect PASS** (render + owner-isolation + the new nav/css + bare-fragment assertions all green).

```bash
./gradlew --no-daemon test --tests "com.robsartin.setlistscout.web.ArtistPageRenderTest" \
  --tests "com.robsartin.setlistscout.web.ShowsPageRenderTest" --console=plain
```

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/templates/artists.html src/test/java/com/robsartin/setlistscout/web/ArtistPageRenderTest.java
git commit -m "#96 PR1: migrate artists.html onto shared layout + design tokens

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: accessibility/contrast verification + full gate

**Files:** none (or a tiny fix to `app.css` if a contrast pair fails). Verification + gate task.

- [ ] **Step 1: Contrast check (both themes).** For each critical token pair, confirm the WCAG contrast ratio meets AA (≥4.5:1 for `--text`/`--muted` body text and chip ink-on-chip-bg; ≥3:1 for `--on-primary` on `--primary` [large/UI] and border/focus visibility). Pairs to check, LIGHT then DARK: `--text`/`--bg`, `--muted`/`--bg`, `--muted`/`--surface`, `--on-primary`/`--primary`, `--member-ink`/`--member-bg`, `--similar-ink`/`--similar-bg`, `--tribute-ink`/`--tribute-bg`, `--good`/`--good-bg`, `--bad`/`--bad-bg`. Compute ratios (any contrast tool / the standard relative-luminance formula). Record the numbers in the task report. If any pair fails, nudge that token (darken ink / lighten bg) and note the change; re-run the render tests.

- [ ] **Step 2: Keyboard + semantics spot-check.** Confirm from the rendered markup: nav links + all buttons are real focusable elements with the `:focus-visible` outline; `aria-current="page"` appears on exactly the active nav item; the `aria-live` regions (`shows-region`, `pending-section`) are intact; no interactive control lost its `<button>`/`<a>` semantics in the migration. (A manual browser pass with Tab is ideal; at minimum verify from the markup + the render-test assertions.)

- [ ] **Step 3: Full gate (FOREGROUND, blocking)**

```bash
export JAVA_HOME=/Users/sartin/.sdkman/candidates/java/21.0.12-tem
python3 scripts/check_adrs.py && echo "ADRs OK"
./gradlew --no-daemon clean build --console=plain
```

Expected: `ADRs OK` and BUILD SUCCESSFUL (unit + Testcontainers render tests + `ModularityTests`; no inline-style regressions; every page still renders through the layout).

- [ ] **Step 4: Commit** any contrast fix (skip if none)

```bash
git add -A && git commit -m "#96 PR1: verify AA contrast + keyboard a11y across both themes

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 5: Final whole-branch review + PR.** (Handled by the subagent-driven-development wrapper: a whole-branch review over `main..HEAD`, a fix wave for anything it surfaces, then push + open the PR to `main` referencing #96. Note in the PR body that this is PR1 of 2 — foundation only, no routing/behavior change; PR2 does the split + grouped candidates. Stop at the PR — do not merge.)

---

## Self-Review

- **Spec coverage (PR1 portion of the design spec §1, §2, §7):** design-system stylesheet with auto light/dark (Task 1), shared layout fragment + top nav (Task 2), migrate existing pages in place (Tasks 2–3), a11y/AA verification (Task 4). The page split + grouped candidates (spec §3–§5) are explicitly PR2, not this plan.
- **No behavior/route change:** every form action, htmx attribute, fragment name, and `th:*` binding is preserved; the render tests assert the htmx fragments still return bare (no layout), which is the one real risk of the layout migration.
- **Auto-only theming:** app.css uses `:root` + the dark media query only — no `[data-theme]` (the mockup's toggle is not shipped). Stated in Global Constraints and Task 1.
- **Placeholder scan:** token values are concrete (from the mockup); test code is real; the one flexible spot (the `aria-current` Thymeleaf attribute idiom) has an explicit fallback and a test that pins the expected output.
- **Type/name consistency:** `fragments/layout :: page(title, active, content)` used identically in Tasks 2–3; fragment ids `shows-region` / `active-section` / `pending-section` preserved and asserted; `active` values `'shows'`/`'artists'` match the nav.
