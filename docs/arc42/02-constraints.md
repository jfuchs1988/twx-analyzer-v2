# 2 — Constraints

## Technical Constraints

- Backend: Java 25, Quarkus 3.40.1.1, Jakarta REST/CDI/Transactions, and Panache/JPA (`pom.xml`).
- Frontend: React 19, TypeScript 6, Vite 8, and Mantine 9; ESM/npm (`frontend/package.json`).
- MariaDB is the configured runtime default; the test profile uses H2.
- Local build scripts require Bash, JDK 25, Maven 3.10.0, Node 24.22.1, and npm 12.2.1. Podman is only required for optional image creation and container runtime operations.
- The runtime image uses Eclipse Temurin 25 JRE; the MariaDB container is pinned to the 12.3.3 LTS line. Existing 11.8.x Windows bind-mounted datadirs require the documented backup/restore migration rather than an in-place image switch.
- Vite writes static assets to the independent `frontend/dist` build directory; the frontend container serves these assets separately from Quarkus.
- The deployed frontend uses same-origin `/api` and `/q` paths through its reverse proxy; cross-origin CORS is not required for the standard deployment.
- BAW integration uses the Operations REST API, TLS, and an optional PEM CA bundle.

## Build and Licensing Metadata

Build and license metadata are present: `pom.xml`, `Containerfile`, `.gitignore`, `LICENSE`, and `NOTICE`. Local builds access Maven Central and npm directly; Podman is needed only for optional container targets.

## Organizational Constraints

- The UI supports German and English; source comments and project documentation use English as the primary language.
- `LICENSE` and `NOTICE` contain licensing and provenance information; changes must be checked against actual rights and dependency data.
- The scripts include guidance for Windows/Git Bash and environment-variable configuration.
