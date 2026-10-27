# ADR-005 — Podman Operations with Loopback Binding

- **Date:** 2026-10 (transcribed and updated to match the current configuration)
- **Status:** Accepted

## Context

The application and database should run locally/in containers without accidentally exposing services to the LAN.

## Decision

`scripts/run.sh` uses Podman with a dedicated `twxav2` network, separate frontend/API containers, and MariaDB. The frontend is published on `127.0.0.1:8080` and proxies API/OpenAPI routes to the API container, which is not published directly; MariaDB is published on `127.0.0.1:8081` for local development access. MariaDB's datadir is bind-mounted from Git-ignored repository path `data/mariadb`; uploaded archives and credentials are in `data/uploads` and `data/db.env`. First-time setup generates database passwords and a `DB_SECRET` in `data/db.env`. `scripts/start-db.sh` starts MariaDB without starting the app. `scripts/run.sh`, `scripts/dev.sh`, and `scripts/preview-local.sh` accept `--skip-db` to reuse an already-running DB without restarting it. Existing named-volume databases are migrated with `scripts/migrate-db-volume.sh` while MariaDB is stopped; remove the old volume only after verifying the bind-mounted DB.

## Rationale

- Loopback binding prevents network access from outside by default.
- The local database port is needed by the development workflow.
- The bind-mounted, Git-ignored `data/` directory retains database files and uploads when containers are recreated.

## Consequences

- Ports 8080 and 8081 must be available locally.
- The Git-ignored repository `data/` directory contains database files, uploaded archives, and secrets and must not be committed or distributed unprotected.
- Container names and network are defined in the scripts.
