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
frontend/                   React/TypeScript UI (Vite)
scripts/                    Local build/test and optional Podman scripts
docs/arc42/                 Architecture documentation, one file per section
docs/adrs/                  Architecture Decision Records
docs/NOTICE-AUDIT.md        Upstream provenance and NOTICE audit
docs/build-versions.md      Reviewed build/runtime versions
WORKLOG.md                  Current open tasks and handoff status
```

The backend serves the API and static UI on port `8080`. MariaDB is the runtime default; tests use H2. The Vite development server proxies `/api` to `http://localhost:8080`. See [`docs/README.md`](docs/README.md) for architecture documentation.

## Prerequisites

- Local build/test: Bash (Git Bash on Windows is supported), JDK 25, Maven 3.10.0, Node.js 24.22.1, and npm 12.2.1.
- Backend: Quarkus 3.40.1.1 (LTS); Java dependencies and build plugins are declared in `pom.xml`.
- Frontend: React 19, Mantine 9, TypeScript 6, and Vite 8; exact dependency versions are locked in `frontend/package-lock.json`.
- Podman: required for `scripts/preview-local.sh`, `scripts/dev.sh`, and container operations. Local build/test alone do not require Podman. The MariaDB image is pinned to 11.8.9 (LTS within the existing major line).
- Direct BAW import: a reachable BAW instance and valid credentials; configure a PEM CA file when the certificate is not trusted by the system.

## Build and test

The default build runs locally and does not require Podman:

```bash
scripts/build.sh              # Local frontend and Quarkus package build; no image
scripts/test.sh               # Local backend tests
scripts/test.sh -Dtest=TwxParserTest
scripts/lint.sh               # Local ESLint
scripts/lint.sh --fix
scripts/build-frontend.sh     # Local UI build into Quarkus resources
scripts/preview-local.sh      # Local app at 127.0.0.1:8080, using persistent Podman MariaDB
scripts/preview-local.sh --skip-db  # Reuse an already-running MariaDB unchanged
scripts/start-db.sh            # Start only the MariaDB container; keep it running
scripts/stop.sh --keep-db      # Stop the app but leave MariaDB running
scripts/dev.sh --skip-db       # Host live reload without restarting MariaDB
```

Build containers only when explicitly requested:

```bash
scripts/build-image.sh        # Package the local target/quarkus-app as a Podman image
scripts/build-container.sh    # Full alternative build inside containers
```

To build the frontend manually:

```bash
cd frontend
npm ci
npm run build
npm run lint
```

Vite writes to `src/main/resources/META-INF/resources`, so the frontend build needs write access to the backend source tree.

## Run with Podman

After explicitly building the image with `scripts/build-image.sh`:

```bash
scripts/run.sh
```

The application is available at <http://127.0.0.1:8080>; Swagger UI is at `/q/swagger-ui`. MariaDB 11.8.9 is published on `127.0.0.1:8081`. Database files persist under `data/mariadb`; credentials and uploaded TWX archives are under `data/db.env` and `data/uploads`. All of `data/` is excluded from Git. Stop only the app while retaining the database with `scripts/stop.sh --keep-db`; stop both containers with:

```bash
scripts/stop.sh
```

To start the containerized app without changing an already-running database, use `scripts/run.sh --skip-db`. `scripts/dev.sh` and `scripts/preview-local.sh` also accept `--skip-db` (or `TWXAV2_SKIP_DB=1`) and leave MariaDB running when the host app stops. Quarkus tests use an ephemeral HTTP test port so they can run while MariaDB retains port `8081`.

See [`docs/build-versions.md`](docs/build-versions.md) for the reviewed versions and the MariaDB exception.

## Configuration

All defaults and environment variables are listed in [`src/main/resources/application.properties`](src/main/resources/application.properties). Key settings:

| Variable | Purpose | Default / notes |
| --- | --- | --- |
| `DB_HOST`, `DB_PORT`, `DB_NAME`, `DB_USER`, `DB_PASSWORD` | Database connection | Local default `127.0.0.1:3306`; Podman scripts set container values |
| `DB_SECRET` | Encryption for stored BAW passwords | First-time Podman setup generates it in Git-ignored `data/db.env`; manage it securely in production. Without it, the implementation stores plaintext |
| `UPLOAD_DIR` | Imported TWX file storage | `${user.dir}/data/uploads`; Podman scripts set it to `data/uploads` |
| `INGEST_CONCURRENCY` | Concurrent ingest tasks | `2` |
| `API_TOKEN` | Protects `/api/export/*` | Empty disables it; it does not protect other endpoints |
| `BPM_DEFAULT_BASE_URL`, `BPM_CA_FILE` | BAW import | Optional; CA file only when needed |
| `BPM_REQUEST_TIMEOUT`, `BPM_EXPORT_TIMEOUT` | BAW HTTP timeouts in seconds | `60` and `600` |
| `TX_TIMEOUT` | Ingest transaction timeout | `600` |
| `GATE_MAX_CRITICAL`, `GATE_MAX_MAJOR`, `GATE_MIN_HEALTH` | Quality gate defaults | `0`, `-1` (unchecked), `70` |
| `MARIADB_IMAGE` | Optional MariaDB image for `scripts/run.sh` | `docker.io/library/mariadb:11.8.9`; major upgrades require backup/restore testing |

All persistent local state is stored in the repository's Git-ignored `data/` directory. First-time setup creates `data/db.env`, `data/uploads/`, and `data/mariadb/`. The scripts resolve `TWXAV2_DATA_DIR` to this directory and reject other locations. Existing Podman volume data must be migrated with `scripts/migrate-db-volume.sh` while MariaDB is stopped; verify the bind-mounted DB before removing the old volume. Never commit credentials, tokens, or TWX files. In particular, `API_TOKEN` does not provide global application authentication.

## License and third-party notices

The project includes [`LICENSE`](LICENSE) (Apache License 2.0) and [`NOTICE`](NOTICE), which contains copyright/provenance information and a short list of relevant third-party components. Include both files when redistributing the project, and verify the complete license terms for all Maven/npm dependencies from their package metadata. `NOTICE` specifically identifies the derived analysis core and Mozilla Rhino.
