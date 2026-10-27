# Build and Runtime Versions

**Reviewed:** 2026-10-27. Versions were checked against official release pages and package registries.

## Selected versions

| Area | Project version | Assessment |
| --- | --- | --- |
| Java/JDK | Java 25 LTS; local build uses JDK 25.0.5+8 | Current Java LTS line; `maven.compiler.release=25` |
| Quarkus | 3.40.1.1 | Current Quarkus 3 LTS release at review time |
| Maven | 3.10.0 | Current stable Maven release; used by the optional builder image and local build |
| Container builder images | `maven:3.10.0-eclipse-temurin-25`, `node:24.22.1-alpine` | Used only by `scripts/build-container.sh`; npm is set to 12.2.1 there |
| Maven Compiler Plugin | 3.16.0 | Latest stable release; Maven 4 beta was intentionally not selected |
| Maven Surefire Plugin | 3.6.1 | Latest stable release |
| JaCoCo | 0.8.16 | Latest stable release |
| Rhino | 1.9.1 | Latest stable release |
| Node.js | 24.22.1 LTS | Current LTS line at review time; Node 26 had not yet entered LTS |
| npm | 12.2.1 | Pinned by build scripts and `frontend/package.json` |
| React / React DOM | 19.3.1 | Latest stable release |
| Mantine | 9.7.4 | Latest stable release; upgrade from 7 to 9 passed the frontend build |
| Tabler Icons | 3.49.0 | Current package release |
| TypeScript | 6.0.3 | Newest version compatible with `typescript-eslint` 8.72.1 (peer range `<6.1`); TypeScript 7 is not supported by that parser yet |
| typescript-eslint | 8.72.1 | Current release |
| ESLint / `@eslint/js` | 10.13.0 / 10.0.1 | Current stable majors; Hooks lint retains the project's established core rules |
| Vite / React plugin | 8.3.5 / 6.1.2 | Current stable releases; Node 24 satisfies their minimum version |
| PostCSS | 8.5.32 | Current patch; Mantine preset 1.18.0 and simple-vars 7.0.1 |
| Runtime JRE image | `eclipse-temurin:25-jre` | Tracks current patches on the Java 25 LTS line |
| MariaDB | 11.8.9 LTS | Newest patch on the existing 11.x line; no automatic switch to a new major with a persistent database volume |

## MariaDB major-version exception

MariaDB 12.3.3 is also an LTS line and is newer. The current MariaDB 11.8.9 datadir is bind-mounted from the Git-ignored `data/mariadb/` path; local tests use H2 rather than MariaDB. The image remains on the 11.x LTS patch until an upgrade, backup, and restore are tested against 12.3.3. `MARIADB_IMAGE` can be overridden for an explicit test; do not do this against production data without a backup.

## Local build

The build, backend tests, and frontend lint run locally without Podman. The local preview and live-reload scripts use Podman MariaDB; image creation requires the explicit `scripts/build-image.sh` or `scripts/build-container.sh` commands. The verified portable tools are in the ignored `temp/toolchain/` directory and are not part of the distribution.

On 2026-10-27, the Vite production build, ESLint, local Quarkus package build, all 453 backend tests, and the JaCoCo gate passed. The Podman MariaDB-backed host preview also passed a health check and UI/API smoke check. The optional application image and MariaDB 12.3 migration test were not run.

## Sources

- [Quarkus 3.40 LTS](https://quarkus.io/blog/quarkus-3-40-released/) and [3.40.1.1](https://quarkus.io/blog/quarkus-3-40-1-1-released/)
- [Java/Temurin support](https://adoptium.net/support)
- [Node.js release schedule](https://nodejs.org/en/about/previous-releases)
- [Apache Maven release history](https://maven.apache.org/docs/history.html)
- [MariaDB 12.3 LTS](https://mariadb.org/mariadb-server-12-3-lts-released/)
- [React releases](https://react.dev/versions), [Mantine releases](https://mantine.dev/changelog/all-releases/), and [Vite 8](https://vite.dev/blog/announcing-vite8)
