# 7 — Deployment View

## Local/container deployment

`scripts/run.sh` starts three containers in a Podman network:

```text
Host 127.0.0.1:8080 ──> twxav2-frontend (Nginx) ──> twxav2-app:8080 ──> twxav2-db:3306
Host 127.0.0.1:8081 ─────────────────────────> twxav2-db:3306
                                  /api and /q ──┘
Repository data/mariadb ── bind mount to twxav2-db:/var/lib/mysql
Repository data/uploads ── bind mount to /app/data/uploads
Repository data/db.env ─── credentials (Git-ignored)
```

The frontend and API are separately built and run; only the frontend is published to the host, and it reverse-proxies `/api` and `/q` to the API over the Podman network. Nginx permits request bodies up to the API's configured 500 MB upload limit. The script binds published ports to loopback. All persistent data is stored under the checkout's Git-ignored `data/` directory. MariaDB is a Podman container whose datadir is bind-mounted from `data/mariadb`.

## Local build and optional image

1. `scripts/build.sh` builds the frontend into `frontend/dist` and the API package into `target/quarkus-app` as separate outputs.
2. `scripts/build-image.sh` builds `twxav2-app:latest`; `scripts/build-frontend-image.sh` independently builds `twxav2-frontend:latest`. `scripts/build-container.sh` remains an explicit full container-build alternative and builds both images.

For local preview against the same persistent database, `scripts/preview-local.sh` runs Quarkus on `127.0.0.1:8081` and Vite's static preview on `127.0.0.1:8080`; Vite proxies API/OpenAPI paths. `scripts/dev.sh` runs Quarkus API live reload on `127.0.0.1:8080`; run `npm run dev` separately from `frontend/` (Vite defaults to port 5173). Both host workflows connect to the Podman MariaDB published on `127.0.0.1:8081`. Use `--skip-db` to require the existing DB without starting or recreating it; `scripts/start-db.sh` can start MariaDB independently. The DB container is left running when the host processes exit. Database files, credentials, and uploaded archives remain under repository `data/`; `.gitignore` excludes them. `scripts/stop.sh --keep-db` stops the frontend/API containers only; default `scripts/stop.sh` also stops the DB container but preserves `data/`. Existing named-volume data is copied with `scripts/migrate-db-volume.sh`; remove the old volume only after verifying the bind-mounted database.

## Verification status

On 2026-10-27, `scripts/build.sh`, all 459 backend tests with the JaCoCo gate, all 9 Vitest tests, and frontend ESLint passed with the pinned JDK, Maven, Node, and npm versions. Podman is unavailable in this environment, so the Nginx/API container stack and MariaDB-backed split preview were not smoke-tested. The API image uses Temurin 25 JRE and starts `quarkus-run.jar`; the frontend image uses Nginx and serves the independent Vite output. Use `scripts/stop.sh` to stop containers; do not remove `data/` without a backup.
