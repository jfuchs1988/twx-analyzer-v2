# Worklog

This file is a current handoff, not a changelog. Resolved work is removed after any durable documentation is updated. See [`AGENTS.md`](AGENTS.md) and [`docs/README.md`](docs/README.md) for the live-documentation policy.

## Current focus

**As of:** 2026-10-27 | **Status:** v2.0.0 is released from `main` at tag [`v2.0.0`](https://github.com/jfuchs1988/twx-analyzer-v2/releases/tag/v2.0.0). `main` is version `2.0.0`; `develop` continues at `2.0.0-SNAPSHOT`. The local release stack is running in Podman against the existing MariaDB datadir; no new database was created. All previous W-001 through W-013 items are resolved.

**Latest verification:** Release-version `scripts/build.sh` passed; all 468 backend tests and the JaCoCo gate passed. Backend tests use ephemeral H2 and do not touch the local MariaDB. All 9 Vitest tests, ESLint, and the frontend build passed. Both Podman images built; the UI/API/OpenAPI smoke test passed against the existing MariaDB 12.3.3 database. The active database passed `mariadb-check`; an 11.8.9 rollback copy is retained. The existing database was restarted with `scripts/start-db.sh`, and the release UI/API/OpenAPI endpoints return HTTP 200. GitHub Actions passed on both `develop` and `main`. Git ignores `data/`; no database files are tracked.

No application login is implemented by design: deployment is restricted to a closed trusted intranet. BAW credentials require HTTPS and a configured trusted origin. `scripts/upgrade-mariadb.sh` is the backup/restore path for existing 11.8.x datadirs; do not switch a Windows bind-mounted old datadir in place.

Git workflow: use `main` (the repository has no `master` branch). Tag `v2.0.0` and the GitHub release are published; no follow-up merge is pending.

## Open tasks

No open tasks.
