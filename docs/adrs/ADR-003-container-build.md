# ADR-003: Local Build by Default; Containers Are Opt-In

- **Date:** 2026-10 (transcribed and updated to reflect the current build policy)
- **Status:** Accepted

## Context

Build dependencies and host toolchains vary, and some developer machines may not have Maven or Node.js. At the same time, container builds are significantly slower than native builds in the local development loop.

## Decision

The default build runs locally through `scripts/build.sh`:

1. Local Node/npm installs frontend dependencies with `npm ci` and builds the Vite assets.
2. Local Maven builds the Quarkus package.
3. A container image is created only when explicitly requested through `scripts/build-image.sh` or `scripts/build-container.sh`.

Local toolchain versions are documented in `docs/build-versions.md`.

## Rationale

- The local feedback loop avoids container overhead.
- A container remains available as an explicit, reproducible distribution option.

## Consequences

- The specified host tool versions must be available for local builds.
- Podman is not required for tests or the normal package build.
- Build the frontend before Maven so the Quarkus package includes the static assets.
