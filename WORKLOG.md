# Worklog

This file is a current handoff, not a changelog. Resolved work is removed after any durable documentation is updated. See [`AGENTS.md`](AGENTS.md) and [`docs/README.md`](docs/README.md) for the live-documentation policy.

## Current focus

**As of:** 2026-10-27 | **Status:** Documentation cleanup and static review are complete. The project was pushed to `develop` through a bootstrap task branch; remote `main` remains unchanged and `master` is reserved for a release.

**Latest verification:** `scripts/build.sh` passed; all 453 backend tests and the JaCoCo gate passed; modified shell scripts pass `bash -n`. Frontend ESLint passed after recent UI changes. Podman health checks and UI/API HTTP 200 checks passed. All 33 Markdown files have zero broken relative links.

Persistent MariaDB files, credentials, and uploads are under Git-ignored `data/mariadb`, `data/db.env`, and `data/uploads`. The old named Podman volume was migrated and removed after verifying the bind-mounted DB. The MariaDB database is fresh; the previous H2 preview had no persistent snapshot metadata, so preserved archives must be re-imported if needed. The host preview runs with `scripts/preview-local.sh --skip-db`; stopping the app leaves MariaDB running.

Git workflow: local `develop` tracks `origin/develop`; the bootstrap task branch was merged and pushed. Remote `main` was left unchanged by request. No `master` release branch was created.

## Open tasks

| ID | Priority | Task | Reference | Status |
| --- | --- | --- | --- | --- |
| W-001 | High | Define authentication and authorization for all API endpoints before exposing the application beyond a protected local network. The optional token only guards exports; unauthenticated BPM settings and test/import endpoints can redirect stored Basic credentials to a caller-selected host. Require HTTPS and a trusted-target policy as part of the fix. | `ApiTokenFilter`, `BpmResource`, `BpmClient`, arc42 §§3/8/11 | Open |
| W-002 | High | Require `DB_SECRET` in production. Document and test secret rotation, recovery, and migration of existing plaintext values. | `SecretsCrypto`, ADR-011, arc42 §§8/11 | Open |
| W-003 | Medium | Complete the license/attribution review for direct and transitive Maven/npm dependencies and provide required distribution notices. | `LICENSE`, `NOTICE`, `docs/NOTICE-AUDIT.md` | Partially complete |
| W-004 | Medium | Bound aggregate TWX expansion/content, per-object payload accumulation, queued uploads, and temporary-storage usage; close ZIP entry streams and test disk-full/restart behavior. Existing limits are primarily per request, entry, or toolkit, not an end-to-end aggregate budget. | `TwxParser`, `SnapshotResource`, `IngestQueue`, `application.properties`, arc42 §11 | Open |
| W-005 | Medium | Decide whether to reactivate the historical build-CA decision in ADR-008; it is not implemented in the current build. | `docs/adrs/ADR-008-historical-build-ca.md`, build environment | Open |
| W-006 | Medium | Validate the optional Podman image against the target environment when a container build is requested. | `scripts/build-image.sh`, `Containerfile` | Deferred until requested |
| W-007 | Medium | Validate a MariaDB 12.3.3 LTS upgrade against a backup/test volume before migrating the existing 11.x data volume. | `scripts/run.sh`, `docs/build-versions.md`, ADR-004 | Open |
| W-008 | High | Neutralize spreadsheet formula-leading values in semicolon CSV exports; add tests for fields beginning with `=`, `+`, `-`, or `@`. | `ExportResource.csvCell`, `ExportApiTest` | Open |
| W-009 | Medium | Avoid materializing every matching object before the search deadline/limit; paginate or stream candidates and profile large snapshots. | `SearchResource.candidates` | Open |
| W-010 | Medium | Batch toolkit graph/inventory lookups; the all-app conflict view recomputes each app graph and the graph traversal queries nested edges and toolkit rows per node. Profile representative large inventories first. | `SnapshotResource.toolkitGraph`, `ToolkitResource.list` | Open |
| W-011 | Medium | Add a frontend component-test harness for critical settings, upload, and status flows; the frontend currently defines build/lint scripts but no test script. | `frontend/package.json`, `frontend/src/pages/RuleSettingsPage.tsx` | Open |
| W-012 | Medium | Define a coverage strategy for Quarkus runtime/API classes. The JaCoCo gate does not instrument some `@QuarkusTest` classes loaded through `QuarkusClassLoader`; the line threshold should not be treated as whole-application coverage. | `pom.xml` JaCoCo configuration | Open |
| W-013 | Low | Add CI automation for the existing local build, backend test, and frontend lint entry points; no checked-in CI workflow was found. | `scripts/build.sh`, `scripts/test.sh`, `scripts/lint.sh` | Open |
