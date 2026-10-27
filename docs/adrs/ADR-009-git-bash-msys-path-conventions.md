# ADR-009 — Git Bash/MSYS Path Conventions

- **Date:** 2026-10-20 (transcribed from the decision record)
- **Status:** Accepted

## Context

Git Bash on Windows converts arguments that look like Unix paths. This can break Podman bind mounts with container paths such as `/src` or `/app`.

## Decision

The shell scripts detect `MSYSTEM`, set `MSYS2_ARG_CONV_EXCL='*'`, and generate host paths with `pwd -W`. Inside containers, use container paths only.

## Rationale

This keeps host mount paths separate from container target paths and makes mounts work under Git Bash.

## Consequences

- On Linux/macOS, the MSYS branch is not used and the scripts use normal paths.
- Manual Podman mount commands from Git Bash must follow the same path convention.
