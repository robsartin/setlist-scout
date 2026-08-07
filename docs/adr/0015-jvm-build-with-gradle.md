---
status: Accepted
date: "2026-08-07"
topic: jvm-build-with-gradle
tags: [language, jvm, build]
supersedes: []
related: [jvm-quality-and-tests, java-conventions, kotlin-conventions, i18n-on-the-jvm]
---
# 15. Build JVM projects with Gradle

## Context

JVM projects need a consistent build tool, dependency management, and package
organization so repositories are predictable to build and navigate, and so shared
tooling (formatting, coverage, arch tests) can be applied the same way everywhere.

## Decision

- **Gradle** with the **Kotlin DSL** (`build.gradle.kts`) is the build tool.
- Dependencies are declared through a **version catalog** (`gradle/libs.versions.toml`)
  for a single source of truth.
- The **JDK is pinned** via a Gradle toolchain so the build uses the same Java version
  everywhere, independent of the machine's default JDK.
- The **group / base package is `com.robsartin.setlistscout`**, with the standard `src/main` and
  `src/test` layout.
- The Gradle wrapper (`./gradlew`) is committed; the build never depends on a
  system-installed Gradle.

## Alternatives considered

- **Maven** — wider historical adoption and a rigid XML lifecycle, but its declarative POM
  model resists the shared custom tooling (arch tests, coverage wiring) this baseline needs.
- **Gradle Groovy DSL** — still common, but untyped and IDE tooling is weaker than the Kotlin
  DSL's compile-time checking and autocomplete, so it was passed over.
- **No pinned toolchain (rely on system JDK)** — simpler to configure, but reintroduces
  "works on my machine" drift, which the pinned toolchain exists to eliminate.

## Consequences

- Builds are reproducible and self-contained via the wrapper and pinned toolchain.
- Versions live in one catalog rather than scattered across build files.
- Contributors run `./gradlew` with the pinned toolchain; a mismatched default JDK cannot
  silently change the build.
- The pinned JDK must be available to Gradle (auto-provisioned or installed); a
  system-default JDK that Gradle can't launch on still needs an explicit workaround.
