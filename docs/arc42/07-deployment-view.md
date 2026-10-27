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

The frontend and API are separately built and run; only the frontend is published to the host, and it reverse-proxies `/api` and `/q` to the API over the Podman network. Nginx permits request bodies up to the API's configured 500 MB upload limit. The script binds published ports to loopback. There is no application login by design; if an operator republishes the UI to an intranet address, network ACLs must restrict access to the trusted intranet. All persistent data is stored under the checkout's Git-ignored `data/` directory. MariaDB is a Podman container whose datadir is bind-mounted from `data/mariadb`.

## Local build and optional image

1. `scripts/build.sh` builds the frontend into `frontend/dist` and the API package into `target/quarkus-app` as separate outputs.
2. `scripts/build-image.sh` builds `twxav2-app:latest`; `scripts/build-frontend-image.sh` independently builds `twxav2-frontend:latest`. `scripts/build-container.sh` remains an explicit full container-build alternative and builds both images.

## Release images

When a stable GitHub Release is published, `.github/workflows/release-images.yml` runs the frontend lint/tests/build and backend tests/package, then builds and publishes both runtime images to GitHub Container Registry (GHCR). The API image is `ghcr.io/jfuchs1988/twx-analyzer-v2-api`; the frontend image is `ghcr.io/jfuchs1988/twx-analyzer-v2-frontend`. Each image receives the release tag (for example `v2.0.0`), normalized semantic-version tags, and `latest`. Pre-releases do not publish images. Because the source repository is private, GHCR packages are private by default and require package-read permission to pull. The images include only the API runtime or static frontend—not MariaDB, credentials, or application data.

To run a published release with the existing local Podman scripts, pull the versioned images and retag them as `twxav2-app:latest` and `twxav2-frontend:latest`; then follow the normal database and runtime setup in the README.

For local preview against the same persistent database, `scripts/preview-local.sh` runs Quarkus on `127.0.0.1:8081` and Vite's static preview on `127.0.0.1:8080`; Vite proxies API/OpenAPI paths. `scripts/dev.sh` runs Quarkus API live reload on `127.0.0.1:8080`; run `npm run dev` separately from `frontend/` (Vite defaults to port 5173). Both host workflows connect to the Podman MariaDB published on `127.0.0.1:8081`. Use `--skip-db` to require the existing DB without starting or recreating it; `scripts/start-db.sh` can start MariaDB independently. The DB container is left running when the host processes exit. Database files, credentials, and uploaded archives remain under repository `data/`; `.gitignore` excludes them. `scripts/stop.sh --keep-db` stops the frontend/API containers only; default `scripts/stop.sh` also stops the DB container but preserves `data/`. Existing named-volume data is copied with `scripts/migrate-db-volume.sh`; for an 11.8.x datadir, start it with the matching 11.8 image and run `scripts/upgrade-mariadb.sh` before using the configured 12.3.3 image. Keep the old volume until the bind-mounted or restored database is verified.

## Verification status

On 2026-10-27, `scripts/build.sh`, all 468 backend tests with the JaCoCo gate, all 9 Vitest tests, and frontend ESLint passed with the pinned JDK, Maven, Node, and npm versions. Both Podman images built; the UI/API/OpenAPI smoke test passed against the existing MariaDB 12.3.3 container without creating a new database. The active database passes `mariadb-check`; an 11.8.9 rollback copy is retained. The local database container was stopped after verification; start that existing database with `scripts/start-db.sh` when needed. The API image uses Temurin 25 JRE and starts `quarkus-run.jar`; the frontend image uses Nginx and serves the independent Vite output. Do not remove database or upload data without a backup.
