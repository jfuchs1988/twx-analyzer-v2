# ADR-001: Quarkus and React Stack

- **Date:** 2026-10 (transcribed from the decision record)
- **Status:** Accepted

## Context

The system needs a container-friendly backend with dependency injection, configuration, REST/OpenAPI, and a separately developed single-page web UI.

## Decision

- Backend: Quarkus 3.40.1.1 with REST, Hibernate ORM with Panache, and SmallRye OpenAPI.
- Persistence: Hibernate ORM with Panache.
- Frontend: React 19, TypeScript 6, Vite 8, and Mantine 9.
- Vite builds static files into `src/main/resources/META-INF/resources`; the backend serves the UI.

## Rationale

- One service provides the API, analysis, and UI.
- Panache reduces persistence boilerplate.
- The frontend build does not need an additional Quarkus/Maven plugin.

## Consequences

- Frontend and backend API contracts must be maintained together.
- Versions are set in `pom.xml`, `frontend/package.json`, and the lockfile and must be reviewed regularly.
