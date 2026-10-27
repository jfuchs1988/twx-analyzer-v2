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
| Rhino | 1.9.1 | Rhino 1.9.2 is the latest stable release (published 2026-10-27); parser compatibility is not yet tested |
| Node.js | 24.22.1 LTS | Latest LTS on 2026-10-27; Node 26.12.1 is still Current and its LTS date is 2026-10-28 |
| npm | 12.2.1 | Pinned by build scripts and `frontend/package.json` |
| React / React DOM | 19.3.1 | Latest stable release |
| Mantine | 9.7.4 | Latest stable release; upgrade from 7 to 9 passed the frontend build |
| Tabler Icons | 3.49.0 | 3.50.0 is the latest npm release; update is not yet applied |
| TypeScript | 6.0.3 | Newest version compatible with `typescript-eslint` 8.72.1 (peer range `<6.1`); TypeScript 7 is not supported by that parser yet |
| typescript-eslint | 8.72.1 | Current release |
| ESLint / `@eslint/js` | 10.13.0 / 10.0.1 | Current stable majors; Hooks lint retains the project's established core rules |
| Vite / React plugin | 8.3.5 / 6.1.2 | Current stable releases; Node 24 satisfies their minimum version |
| Frontend component testing | Vitest 5.0.5, Testing Library React 16.3.3, user-event 14.6.8, jest-dom 7.0.1, jsdom 30.1.3 | Resolved versions in `frontend/package-lock.json`; used by `npm test` |
| PostCSS | 8.5.32 | Current patch; Mantine preset 1.18.0 and simple-vars 7.0.1 |
| Runtime JRE image | `eclipse-temurin:25-jre` | Tracks current patches on the Java 25 LTS line |
| Frontend runtime image | `nginx:1.29-alpine` | Official Nginx Alpine image for separately serving Vite assets and proxying the API |
| Podman CLI (local) | 6.1.4 | Latest stable release checked on 2026-10-27; Podman has no LTS track and is not pinned by the project |
| MariaDB | 11.8.9 LTS | Newest patch on the existing 11.x line; no automatic switch to a new major with a persistent database volume |

`npm outdated --all` also reported 96 transitive/optional entries, mostly major versions outside parent-package ranges or platform-specific optional packages not installed on Windows. One compatible transitive patch was identified: `postcss-selector-parser` 7.1.6 to 7.1.7; it was not updated during this audit.

## Audit findings

The audit checked the checked-in direct dependencies and build tools against official release pages and package registries on 2026-10-27. LTS products use the latest applicable supported line unless an exception is recorded below; libraries and plugins generally do not publish an LTS track.

| Component | Finding | Follow-up |
| --- | --- | --- |
| Rhino | 1.9.2 was released on 2026-10-27; this project still uses 1.9.1. | Upgrade only after the JavaScript parser/rule regression suite passes. |
| Tabler Icons | 3.50.0 is the latest npm release; this project still uses 3.49.0. | Review the changelog, update, and run the frontend build. |
| TypeScript | 7.0.2 is the latest release, but `typescript-eslint` 8.72.1 declares peer support only for TypeScript `<6.1.0`. | Keep 6.0.3 until the lint toolchain supports TypeScript 7, then re-evaluate. |
| Node.js | 24.22.1 is the latest LTS on the review date. Node 26.12.1 is still Current; its scheduled LTS transition is 2026-10-28. | Recheck the official status after the transition before changing the pinned toolchain. |
| MariaDB | 12.3.3 is the newest LTS line; 11.8.9 is the newest patch on the current 11.8 line. An isolated test datadir initialized on 11.8.9 was copied and upgraded on 12.3.3; integrity checks passed. | Keep the existing datadir on 11.8.9 until its own verified backup and rollback/restore plan are ready; the isolated test did not modify or validate the live dataset. |

No dependency version was changed as part of this audit.

## MariaDB major-version exception

MariaDB 12.3.3 is also an LTS line and is newer. Local tests use H2 rather than MariaDB. On 2026-10-27, an isolated test datadir initialized with MariaDB 11.8.9 was cleanly stopped, copied to a separate volume, and started with the official 12.3.3 image using `MARIADB_AUTO_UPGRADE=1`. The server reported 12.3.3; synthetic InnoDB rows and an index remained valid, `mariadb-check --all-databases --check` passed, and the preserved 11.8.9 volume remained readable. The existing repository-backed datadir was not changed. Before migrating it, take and verify a backup of that datadir and test the upgrade and rollback against a disposable copy of that backup. Keep the configured image on 11.8.9 until then. `MARIADB_IMAGE` can be overridden for an explicit isolated test; do not use it against the existing data directory without a verified backup.

## Local build

The build, backend tests, frontend tests (`npm test`), and frontend lint run locally without Podman. The local preview and live-reload scripts use Podman MariaDB; separate API and frontend images are created only by the explicit image build scripts. The verified portable tools are in the ignored `temp/toolchain/` directory and are not part of the distribution.

On 2026-10-27, the Vite production build, ESLint, all 9 Vitest tests, Quarkus package build, all 459 backend tests, and the JaCoCo gate passed. Both separate Podman images built, and the frontend container served its production page with HTTP 200. The isolated MariaDB 11.8.9-to-12.3.3 test-volume upgrade and integrity checks passed. The full MariaDB-backed split application runtime and migration of the existing data directory were not tested.

## Sources

- [Quarkus 3.40 LTS](https://quarkus.io/blog/quarkus-3-40-released/) and [3.40.1.1](https://quarkus.io/blog/quarkus-3-40-1-1-released/)
- [Java/Temurin support](https://adoptium.net/support)
- [Node.js release schedule](https://nodejs.org/en/about/previous-releases)
- [Apache Maven release history](https://maven.apache.org/docs/history.html)
- [MariaDB 12.3 LTS](https://mariadb.org/mariadb-server-12-3-lts-released/)
- [React releases](https://react.dev/versions), [Mantine releases](https://mantine.dev/changelog/all-releases/), and [Vite 8](https://vite.dev/blog/announcing-vite8)
- [Rhino releases](https://github.com/mozilla/rhino/releases), [Tabler Icons npm releases](https://www.npmjs.com/package/@tabler/icons-react), and [typescript-eslint supported dependency versions](https://typescript-eslint.io/users/dependency-versions)
- [Podman releases](https://github.com/podman-container-tools/podman/releases), [JaCoCo releases](https://github.com/jacoco/jacoco/releases), and [Maven Compiler Plugin](https://maven.apache.org/plugins/maven-compiler-plugin/plugin-info.html)
- [npm outdated command](https://docs.npmjs.com/cli/v12/commands/npm-outdated)
