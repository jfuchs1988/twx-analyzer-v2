# Worklog

This file is a current handoff, not a changelog. Resolved work is removed after any durable documentation is updated. See [`AGENTS.md`](AGENTS.md) and [`docs/README.md`](docs/README.md) for the live-documentation policy.

## Current focus

**As of:** 2026-10-27 | **Status:** The non-snapshot `2.0.0` release version is prepared on `release/v2.0.0`, based on local `main` after the confirmed `develop` merge. GitHub Actions passed on `develop`; implementation tasks are resolved. The main push, tag, and GitHub publication remain.

**Latest verification:** Release-version `scripts/build.sh` passed; all 468 backend tests and the JaCoCo gate passed. All 9 Vitest tests, ESLint, and the frontend build passed. Both Podman images built; the UI/API/OpenAPI smoke test passed against the existing MariaDB 12.3.3 database. The active database passed `mariadb-check`; an 11.8.9 rollback copy is retained. The local DB container is stopped as requested; restart that same datadir with `scripts/start-db.sh` when needed. GitHub CI passed for the pushed `develop` commit.

No application login is implemented by design: deployment is restricted to a closed trusted intranet. BAW credentials require HTTPS and a configured trusted origin. `scripts/upgrade-mariadb.sh` is the backup/restore path for existing 11.8.x datadirs; do not switch a Windows bind-mounted old datadir in place.

Git workflow: use `main` (the repository has no `master` branch). The local `main` includes the develop merge; push `main` and tag `v2.0.0` after the release build passes.

## Open tasks

No open implementation tasks. Create the tag and GitHub release after the final release build and CI checks.
