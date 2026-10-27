# Repository Guidelines

## Project overview

- `src/main/java/io/twxav2/`: Java backend (Quarkus/Jakarta REST, Panache/JPA).
- `src/main/resources/`: Runtime configuration and rule text.
- `src/test/java/`: JUnit/Quarkus tests.
- `frontend/`: React, TypeScript, and Vite UI.
- `scripts/`: Local build/test and optional Podman scripts.
- `docs/`: arc42 architecture documentation and Architecture Decision Records (ADRs).

## Changes

- Preserve the existing layers and naming conventions. Keep backend API contracts in sync with the types and calls in `frontend/src/api.ts`.
- Register new rules through the existing rule mechanism and add focused tests.
- Do not commit TWX exports, credentials, tokens, database files, or generated build outputs.
- Never refer to developer-local test data in repository documentation or code. Do not include local test-file paths, filenames, contents, or identifying metadata; tests must use generated synthetic data or sanitized fixtures maintained in the test tree.
- Supply secrets only through the existing secure configuration paths. Set `DB_SECRET` in production; `data/db.env` contains credentials and is excluded from Git.
- Write code comments, scripts, and project documentation in English. Keep German only in intentional localization resources and bilingual UI/backend translations.
- Update affected documentation in the same change as code, configuration, or behavior; do not defer doc updates.

## Build and tests

The default entry points `scripts/build.sh`, `scripts/test.sh`, `scripts/lint.sh`, `scripts/dev.sh`, and `scripts/preview-local.sh` run locally. `scripts/dev.sh` and `scripts/preview-local.sh` use the persistent Podman MariaDB; use `--skip-db` or `TWXAV2_SKIP_DB=1` to require the existing service without restarting it. `scripts/start-db.sh` starts only MariaDB, and `scripts/stop.sh --keep-db` stops only the app. Podman is also needed for `scripts/run.sh`, `scripts/build-image.sh`, and `scripts/build-container.sh`. Frontend dependencies are defined by `frontend/package.json` and `frontend/package-lock.json`.

The root Maven project is `pom.xml`. Local builds use JDK 25 and Maven 3.10.0; the frontend uses Node.js 24.22.1 and npm 12.2.1. The optional runtime image is defined by `Containerfile`. Review manifests and affected scripts together when changing build or runtime behavior.

License notices are in `LICENSE` and `NOTICE`. Preserve these files when redistributing; do not infer complete Maven/npm dependency licensing from the short list in `NOTICE` alone.

## Git workflow

- Use one dedicated Git worktree and topic branch for each new task; do not develop directly on `develop` or `master`.
- Merge reviewed, tested topic branches into `develop`.
- After confirming the merge in `develop`, automatically delete the merged topic branch locally and remotely. Never delete `develop` or `master` through this cleanup.
- Merge `develop` into `master` only when the user confirms the release state is ready.
- Do not force-push or rewrite shared branch history. Preserve local uncommitted changes when creating worktrees or reconciling branches.

## Documentation (living)

- Keep checked-in documentation synchronized with the current source, not a later cleanup pass. `docs/README.md` defines documentation ownership and maintenance.
- `README.md`: setup, prerequisites, build/run workflows, and user-facing configuration.
- `docs/arc42/`: current architecture, runtime, deployment, security, persistence, and API behavior (one Markdown file per section).
- `docs/adrs/`: decisions, not a substitute for current implementation docs. Record only verifiable decisions and label reconstructed history clearly.
- `WORKLOG.md`: active work, unresolved tasks, and the latest relevant verification only. Remove resolved items once durable documentation is updated; do not use it as a changelog.
- Check relative links and ensure version/review-date claims remain accurate whenever related docs change.
