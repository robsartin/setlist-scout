---
status: Accepted
date: "2026-08-07"
topic: hosting-on-render-requires-a-dockerfile-not-native-java-support
tags: [infrastructure, hosting]
supersedes: [hosting-on-render]
related: [hosting-on-render, jvm-build-with-gradle]
---
# 16. Hosting on Render requires a Dockerfile, not native Java support

## Context

[0008](0008-hosting-on-render.md) assumed Render's build would auto-detect a
JVM/Maven project the way it auto-detects Node, Python, Ruby, Go, Rust, and
Elixir. That assumption was wrong: Render has no native Java/JVM runtime.
Render's own docs list the natively supported languages, and Java is not
among them — a JVM app can only be deployed to Render via a **Docker**
environment.

## Decision

Add a multi-stage `Dockerfile` at the repo root:

- **Build stage** — `gradle:8.14.3-jdk21`, runs `gradle clean bootJar`. The
  image's preinstalled Gradle (pinned to the same 8.14.3 as the wrapper) is
  used instead of `./gradlew` so the build stage does not re-download a
  Gradle distribution -- a redundant, transient failure point. `./gradlew`
  remains the entry point for local and CI builds, where Gradle is not
  preinstalled.
- **Runtime stage** — `eclipse-temurin:21-jre-alpine`, copies out only the
  built jar. Keeps the deployed image to a JRE, not a full JDK + Gradle
  toolchain.

On Render, the web service's Environment is set to **Docker** (not the
absent auto-detected Gradle/Java option). Build Command and Start Command
are left blank; the Dockerfile's `ENTRYPOINT` is what runs.

## Alternatives considered

- **Wait for/request native Java support from Render** — not something this
  project controls, and Docker support already covers the need today.
- **Single-stage Dockerfile using only the JDK image** — simpler, but ships
  the full Gradle distribution and build cache into the runtime image,
  bloating it for no runtime benefit.
- **A buildpack (e.g. Cloud Native Buildpacks / Paketo)** — auto-detects
  JVM apps without a hand-written Dockerfile, but Render's Docker
  environment does not run buildpacks itself; using one would mean invoking
  `pack build` in CI and pushing a prebuilt image, which is more moving
  parts than a plain multi-stage Dockerfile for one small service.

## Consequences

- The build now happens twice on every deploy conceptually (Docker's build
  stage re-runs `gradle clean bootJar` inside the image), rather than
  Render building once natively -- an accepted cost of not having a native
  runtime.
- The Dockerfile becomes something to keep in sync with `build.gradle.kts`
  (JDK version, entry jar name) -- a small but real maintenance surface that
  a native runtime would not require.
- Local `docker build .` can be used to reproduce the exact Render build
  environment for debugging, which a native-runtime deploy would not offer.
