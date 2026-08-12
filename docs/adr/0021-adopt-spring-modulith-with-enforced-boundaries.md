# 0021: Adopt Spring Modulith with enforced boundaries

Date: 2026-08-12
Status: Accepted

## Context

The application grew as a single flat package structure with cross-cutting concerns
spread across the codebase. Controllers like `ArtistController` and `ShowController`
resided in a shared layer, a `config` grab-bag held unrelated configuration, and
nothing prevented any class from reaching directly into any other. As the codebase
grows, this lack of structure compounds — adding new features requires understanding
and potentially modifying distant, seemingly unrelated modules; refactoring becomes
fragile; and accidental dependencies silently accumulate.

A modular monolith — with enforced boundaries, not just documented ones — catches
violations before they become entrenched, and makes the cost of coupling explicit.

## Decision

Adopt **Spring Modulith** (BOM 1.3.12) to establish and enforce module boundaries:

- Modules are the direct sub-packages of `com.robsartin.setlistscout`: `catalog`, `scan`,
  `expansion`, `settings`, `review`, `shared`.
- A test, `ModularityTests`, runs `ApplicationModules.of(SetlistScoutApplication.class).verify()`
  in CI. Any illegal cross-module dependency or cycle causes the build to fail.
- The `shared` module is declared OPEN (via `shared/package-info.java`), exposing its
  utility sub-packages (e.g., `observability`) for use by other modules.
- In Phase A, a module's public API is its top-level types — repositories, entities, and
  services. Tighter encapsulation via `.internal` packages and service-only DTOs is
  deliberately deferred to Phase B.
- Application-wide configuration (`AppProperties`, `SecurityConfig`) remains in the
  application root package, accessible to all modules.
- The redesign was completed via the Mikado Method (ADR-0012), keeping the build green
  at each step.

## Consequences

- Boundary violations now fail the CI build, not code review. New cross-module interactions
  must go through exposed types.
- The flat `web` and `config` packages are eliminated; each module now owns its controllers,
  keeping HTTP concerns close to the domain logic they serve.
- The current permissive public-API model (top-level types) is a pragmatic starting point;
  Phase B will tighten encapsulation, but modules are structurally established now.
- Sets up the foundation for event-driven decoupling (ADR-0022), making cross-module writes
  durable and decoupled.
