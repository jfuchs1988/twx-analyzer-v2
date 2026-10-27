# TWX Analyzer v2

TWX Analyzer v2 analyzes IBM Business Automation Workflow (BAW) and Process Designer TWX exports. Its web UI manages imported project and toolkit snapshots and provides quality findings, search, comparisons, and toolkit dependency views.

## Features

- Upload, parse, analyze, and manage TWX snapshots.
- Rule-based quality findings with configurable severity, exclusions, and status.
- Dashboard, project/toolkit overview, search, snapshot and object comparison.
- TWX object details, process diagrams, variables, and toolkit dependencies.
- Optional read-only direct import from BAW through its Operations REST API.
- JSON/CSV/HTML finding exports, optionally protected by an API token.
- German/English UI and light/dark themes.

## Architecture and repository layout

```text
src/main/java/io/twxav2/    Java backend: REST API, TWX ingest, analysis, persistence
src/main/resources/         Quarkus configuration and rule text
src/test/java/              Backend tests
frontend/                   React/TypeScript UI (Vite), built independently
scripts/                    Local build/test and optional Podman scripts
docs/arc42/                 Architecture documentation, one file per section
docs/adrs/                  Architecture Decision Records
docs/NOTICE-AUDIT.md        Upstream provenance and NOTICE audit
docs/build-versions.md      Reviewed build/runtime versions
WORKLOG.md                  Current open tasks and handoff status
```

The React UI is organized by feature under `frontend/src/features/`; app navigation and shared UI/API utilities live under `app/` and `shared/`. In deployment, Nginx serves the static UI and reverse-proxies `/api` and `/q` to the separately running Quarkus API. MariaDB is the runtime default; tests use H2. The Vite development and preview servers proxy `/api` and `/q` to Quarkus. See [`docs/README.md`](docs/README.md) for architecture documentation.

## Prerequisites

- Local build/test: Bash (Git Bash on Windows is supported), JDK 25, Maven 3.10.0, Node.js 24.22.1, and npm 12.2.1.
- Backend: Quarkus 3.40.1.1 (LTS); Java dependencies and build plugins are declared in `pom.xml`.
- Frontend: React 19, Mantine 9, TypeScript 6, and Vite 8; exact dependency versions are locked in `frontend/package-lock.json`.
- Podman: required for `scripts/preview-local.sh`, `scripts/dev.sh`, and container operations. Local build/test alone do not require Podman. The MariaDB image is pinned to 11.8.9 (LTS within the existing major line).
- Direct BAW import: a reachable BAW instance and valid credentials; configure a PEM CA file when the certificate is not trusted by the system.

## Build and test

The default build runs locally and does not require Podman:

```bash
scripts/build.sh              # Build frontend/dist and Quarkus package independently; no image
scripts/test.sh               # Local backend tests
scripts/test.sh -Dtest=TwxParserTest
cd frontend && npm test       # Frontend component tests (Vitest + Testing Library)
scripts/lint.sh               # Local ESLint
scripts/lint.sh --fix
scripts/build-frontend.sh     # Local UI build into frontend/dist
scripts/preview-local.sh      # UI :8080 and API :8081, using persistent Podman MariaDB
scripts/preview-local.sh --skip-db  # Reuse an already-running MariaDB unchanged
scripts/start-db.sh            # Start only the MariaDB container; keep it running
scripts/stop.sh --keep-db      # Stop frontend/API containers; leave MariaDB running
scripts/dev.sh --skip-db       # Start API live reload; run Vite separately from frontend/
```

Build containers only when explicitly requested:

```bash
scripts/build-image.sh        # Package the local target/quarkus-app as a Podman image
scripts/build-frontend-image.sh # Package frontend/dist as a separate Nginx image
scripts/build-container.sh    # Full alternative build inside containers
```

To build the frontend manually:

```bash
cd frontend
npm ci
npm run build
npm run lint
npm test
```

Vite writes to the ignored `frontend/dist` directory. It does not write into the Quarkus source tree. `TWXAV2_API_PROXY_TARGET` can override the local Vite proxy target; its default is `http://localhost:8080`.

## Run with Podman

Build the independently deployable API and frontend images, then start the Podman stack:

```bash
scripts/build.sh
scripts/build-image.sh
scripts/build-frontend-image.sh
scripts/run.sh
```

Nginx serves the UI on <http://127.0.0.1:8080> and proxies `/api` and `/q` to the Quarkus API container, which is not published directly to the host. Swagger UI is available at `/q/swagger-ui`. MariaDB 11.8.9 is published on `127.0.0.1:8081`. Database files persist under `data/mariadb`; credentials and uploaded TWX archives are under `data/db.env` and `data/uploads`. All of `data/` is excluded from Git. Stop the frontend and API while retaining the database with `scripts/stop.sh --keep-db`; stop the full stack with:

```bash
scripts/stop.sh
```

To start the containerized stack without changing an already-running database, use `scripts/run.sh --skip-db`. `scripts/dev.sh` and `scripts/preview-local.sh` also accept `--skip-db` (or `TWXAV2_SKIP_DB=1`) and leave MariaDB running when the host processes stop. Quarkus tests use an ephemeral HTTP test port so they can run while MariaDB retains port `8081`.

See [`docs/build-versions.md`](docs/build-versions.md) for the reviewed versions and the MariaDB exception.

## Configuration

All defaults and environment variables are listed in [`src/main/resources/application.properties`](src/main/resources/application.properties). Key settings:

| Variable | Purpose | Default / notes |
| --- | --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Database connection | Local default `127.0.0.1:3306`; Podman scripts set container values |
| `DB_SECRET` | Encryption for stored BAW passwords | Required in the production profile. First-time Podman setup generates it in Git-ignored `data/db.env`; protect and back it up with the database. |
| `DB_SECRET_PREVIOUS` | One-time key rotation | Optional; set to the old key only for the rotation startup, then remove it. |
| `UPLOAD_DIR` | Imported TWX file storage | `${user.dir}/data/uploads`; Podman scripts set it to `data/uploads` |
| `INGEST_CONCURRENCY` | Concurrent ingest tasks | `2` |
| `API_TOKEN` | Protects `/api/export/*` | Empty disables it; it does not protect other endpoints |
| `BPM_DEFAULT_BASE_URL`, `BPM_CA_FILE` | BAW import | Optional; CA file only when needed |
| `BPM_REQUEST_TIMEOUT`, `BPM_EXPORT_TIMEOUT` | BAW HTTP timeouts in seconds | `60` and `600` |
| `TX_TIMEOUT` | Ingest transaction timeout | `600` |
| `GATE_MAX_CRITICAL`, `GATE_MAX_MAJOR`, `GATE_MIN_HEALTH` | Quality gate defaults | `0`, `-1` (unchecked), `70` |
| `MARIADB_IMAGE` | Optional MariaDB image for `scripts/run.sh` | `docker.io/library/mariadb:11.8.9`; major upgrades require backup/restore testing |

The separately deployed Nginx frontend accepts `API_UPSTREAM` as a `host:port` value (default `twxav2-app:8080` on the Podman network). Its request-body limit is 500 MB and should remain aligned with the Quarkus limit.

All persistent local state is stored in the repository's Git-ignored `data/` directory. First-time setup creates `data/db.env`, `data/uploads/`, and `data/mariadb/`. The scripts resolve `TWXAV2_DATA_DIR` to this directory and reject other locations. Existing Podman volume data must be migrated with `scripts/migrate-db-volume.sh` while MariaDB is stopped; verify the bind-mounted DB before removing the old volume. Never commit credentials, tokens, or TWX files. In particular, `API_TOKEN` does not provide global application authentication.

### Database-secret rotation and recovery

Back up the database and securely retain the current `DB_SECRET` together before changing keys. To rotate, deploy the new value as `DB_SECRET` and the old value as `DB_SECRET_PREVIOUS` for one startup. Startup transactionally re-encrypts the stored BAW password; a bad old key aborts startup and does not replace the stored value. Verify the BPM connection, then remove `DB_SECRET_PREVIOUS` from the runtime environment. Existing plaintext BAW passwords are encrypted automatically at startup once `DB_SECRET` is configured. Production startup fails if `DB_SECRET` is missing.

For recovery, restore a mutually consistent database backup and its matching `DB_SECRET`. A lost key cannot be reconstructed from ciphertext; if no matching backup/key pair exists, the BAW password must be entered again through settings. Do not rotate by changing `DB_SECRET` alone while encrypted values remain in the database.

## License and third-party notices

The project includes [`LICENSE`](LICENSE), [`NOTICE`](NOTICE), and the resolved [`third-party dependency inventory`](docs/THIRD-PARTY-NOTICES.md). Include these notices when redistributing the source or either container image. The inventory covers all declared Maven dependency scopes (including tests) and all npm lockfile packages (including dev tooling), records package versions and license metadata, and identifies the key runtime LGPL/MPL components. It is metadata-based and is not a legal opinion; review applicable upstream license terms before redistribution. Regenerate it after dependency changes with the Maven license plugin and `python scripts/third-party-notices.py`.
