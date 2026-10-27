# Worklog

This file is a current handoff, not a changelog. Resolved work is removed after any durable documentation is updated. See [`AGENTS.md`](AGENTS.md) and [`docs/README.md`](docs/README.md) for the live-documentation policy.

## Current focus

**As of:** 2026-10-27 | **Status:** Release candidate `v2.0.0` is on `task/release-v2.0.0`, based on local `develop` at `7beaac0`. W-001, W-004, W-005, W-006, W-007, and W-010 are addressed; the GitHub Actions workflow for W-013 is configured. Local `develop` is four commits ahead of `origin/develop`; develop/main merges, pushes, and the release tag are pending.

**Latest verification:** `scripts/build.sh` passed; all 468 backend tests and the JaCoCo gate passed. All 9 Vitest tests, ESLint, and the frontend build passed. Both Podman images built; the UI/API/OpenAPI smoke test passed against the existing MariaDB 12.3.3 database. The active database passes `mariadb-check`; its 11.8.9 rollback copy is retained. The local DB container is stopped as requested; restart the existing database with `scripts/start-db.sh` when needed.

No application login is implemented by design: deployment is restricted to a closed trusted intranet. BAW credentials require HTTPS and a configured trusted origin. `scripts/upgrade-mariadb.sh` is the backup/restore path for existing 11.8.x datadirs; do not switch a Windows bind-mounted old datadir in place.

Git workflow: use local `main` (the repository has no `master` branch); the release candidate has not been pushed or tagged yet. The GitHub Actions workflow is configured; its first remote run awaits the push.

## Open tasks

| ID | Priority | Task | Reference | Status |
| --- | --- | --- | --- | --- |
| W-013 | Low | Confirm the configured GitHub Actions workflow on the first remote run. | `.github/workflows/ci.yml` | Pending push; local equivalent build/test/lint checks passed |
