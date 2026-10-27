# 7 — Deployment View

## Local/container deployment

`scripts/run.sh` starts two containers in a Podman network:

```text
Host 127.0.0.1:8080 ──> twxav2-app:latest ──> twxav2-db:3306
Host 127.0.0.1:8081 ─────────────────────────> twxav2-db:3306
Repository data/mariadb ── bind mount to twxav2-db:/var/lib/mysql
Repository data/uploads ── bind mount to /app/data/uploads
Repository data/db.env ─── credentials (Git-ignored)
```

The script binds published ports to loopback. All persistent data is stored under the checkout's Git-ignored `data/` directory. MariaDB is a Podman container whose datadir is bind-mounted from `data/mariadb`.

## Local build and optional image

1. `scripts/build.sh` builds the frontend locally into `src/main/resources/META-INF/resources`.
2. Maven builds the Quarkus package locally into `target/quarkus-app`.
3. Only when explicitly requested, `scripts/build-image.sh` uses Podman to create `twxav2-app:latest`. `scripts/build-container.sh` remains an explicit full container-build alternative.

For local live reload against the same persistent database, `scripts/preview-local.sh` and `scripts/dev.sh` run Quarkus on the host at `127.0.0.1:8080` and connect to the Podman MariaDB published on `127.0.0.1:8081`. Use `--skip-db` to require the existing DB without starting or recreating it; `scripts/start-db.sh` can start MariaDB independently. The DB container is left running when the host app exits. Database files, credentials, and uploaded archives remain under repository `data/`; `.gitignore` excludes them. `scripts/stop.sh --keep-db` stops only the containerized app; default `scripts/stop.sh` also stops the DB container but preserves `data/`. Existing named-volume data is copied with `scripts/migrate-db-volume.sh`; remove the old volume only after verifying the bind-mounted database.

## Verification status

The local frontend/Quarkus build and all 453 backend tests passed on 2026-10-27 with JDK 25, Maven 3.10.0, Node 24.22.1, and npm 12.2.1. The Podman MariaDB health check and host preview/UI/API smoke checks passed with `--skip-db`, confirming the database stayed running. The optional application image was not built. The runtime image uses Temurin 25 JRE and starts `quarkus-run.jar`. Use `scripts/stop.sh` to stop containers; do not remove `data/` without a backup.
