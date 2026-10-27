# Worklog

This file is a current handoff, not a changelog. Resolved work is removed after any durable documentation is updated. See [`AGENTS.md`](AGENTS.md) and [`docs/README.md`](docs/README.md) for the live-documentation policy.

## Current focus

**As of:** 2026-10-27 | **Status:** W-002, W-003, W-008, W-009, W-011, and W-012 are implemented and verified on the unmerged `task/selected-todos` branch. Feature-based frontend modularization and independent Nginx/API deployment are implemented on the unmerged `task/modular-frontend-separate-deploy` branch.

**Latest verification:** `scripts/build.sh` passed; all 459 backend tests and the JaCoCo gate passed. All 9 Vitest tests, frontend production build, and ESLint passed. Bash syntax checks passed for the changed scripts. Podman is unavailable in this environment, so the separate frontend/API images and the split runtime were not built or smoke-tested.

Persistent MariaDB files, credentials, and uploads are under Git-ignored `data/mariadb`, `data/db.env`, and `data/uploads`. The old named Podman volume was migrated and removed after verifying the bind-mounted DB. The MariaDB database is fresh; the previous H2 preview had no persistent snapshot metadata, so preserved archives must be re-imported if needed. The host preview runs with `scripts/preview-local.sh --skip-db`; stopping the app leaves MariaDB running.

Git workflow: `task/modular-frontend-separate-deploy` is based on `develop`; these changes have not been committed, merged, or pushed.

## Open tasks

| ID | Priority | Task | Reference | Status |
| --- | --- | --- | --- | --- |
| W-001 | High | Define authentication and authorization for all API endpoints before exposing the application beyond a protected local network. The optional token only guards exports; unauthenticated BPM settings and test/import endpoints can redirect stored Basic credentials to a caller-selected host. Require HTTPS and a trusted-target policy as part of the fix. | `ApiTokenFilter`, `BpmResource`, `BpmClient`, arc42 §§3/8/11 | Open |
| W-004 | Medium | Bound aggregate TWX expansion/content, per-object payload accumulation, queued uploads, and temporary-storage usage; close ZIP entry streams and test disk-full/restart behavior. Existing limits are primarily per request, entry, or toolkit, not an end-to-end aggregate budget. | `TwxParser`, `SnapshotResource`, `IngestQueue`, `application.properties`, arc42 §11 | Open |
| W-005 | Medium | Decide whether to reactivate the historical build-CA decision in ADR-008; it is not implemented in the current build. | `docs/adrs/ADR-008-historical-build-ca.md`, build environment | Open |
| W-006 | Medium | Validate the separate frontend/API Podman images and reverse-proxy runtime in the target environment. | `scripts/build-image.sh`, `scripts/build-frontend-image.sh`, `Containerfile`, `Containerfile.frontend` | Deferred: Podman unavailable in current environment |
| W-007 | Medium | Validate a MariaDB 12.3.3 LTS upgrade against a backup/test volume before migrating the existing 11.x data volume. | `scripts/run.sh`, `docs/build-versions.md`, ADR-004 | Open |
| W-010 | Medium | Batch toolkit graph/inventory lookups; the all-app conflict view recomputes each app graph and the graph traversal queries nested edges and toolkit rows per node. Profile representative large inventories first. | `SnapshotResource.toolkitGraph`, `ToolkitResource.list` | Open |
| W-013 | Low | Add CI automation for the existing local build, backend test, and frontend lint entry points; no checked-in CI workflow was found. | `scripts/build.sh`, `scripts/test.sh`, `scripts/lint.sh` | Open |
