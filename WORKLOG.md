# Worklog

This file is a current handoff, not a changelog. Resolved work is removed after any durable documentation is updated. See [`AGENTS.md`](AGENTS.md) and [`docs/README.md`](docs/README.md) for the live-documentation policy.

## Current focus

**As of:** 2026-10-27 | **Status:** Release candidate source is merged and pushed to `develop` at `bd2edc5`; GitHub Actions run `40595644053` passed. All previous W-001 through W-013 items are resolved. The remaining release steps are merge to `main`, set the non-snapshot release version, tag `v2.0.0`, and publish the GitHub release.

**Latest verification:** `scripts/build.sh` passed; all 468 backend tests and the JaCoCo gate passed. All 9 Vitest tests, ESLint, and the frontend build passed. Both Podman images built; the UI/API/OpenAPI smoke test passed against the existing MariaDB 12.3.3 database. The active database passed `mariadb-check`; an 11.8.9 rollback copy is retained. The local DB container is stopped as requested; restart the existing database with `scripts/start-db.sh` when needed. GitHub CI passed for the pushed `develop` commit.

No application login is implemented by design: deployment is restricted to a closed trusted intranet. BAW credentials require HTTPS and a configured trusted origin. `scripts/upgrade-mariadb.sh` is the backup/restore path for existing 11.8.x datadirs; do not switch a Windows bind-mounted old datadir in place.

Git workflow: use `main` (the repository has no `master` branch). The release candidate is pushed to `develop`; `main` still awaits the confirmed release merge/tag.

## Open tasks

No open implementation tasks. Release steps are listed above.
