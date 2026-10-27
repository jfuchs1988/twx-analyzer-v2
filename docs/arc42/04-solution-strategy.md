# 4 — Solution Strategy

| Decision/approach | Rationale or visible benefit |
| --- | --- |
| Quarkus backend with REST and JPA/Panache | API, persistence, and ingest/analysis in one service |
| React SPA served by the backend | Single browser origin; same-origin API calls |
| Separate TWX parsing and analysis phases | Domain-specific extraction and rule checks remain independently testable |
| Registered rule components and a central `RuleEngine` | Rules are sorted by ID, configurable, and isolated on failure |
| Bounded background queue | Upload/BAW import does not block the UI request; status can be queried |
| MariaDB at runtime, H2 in tests | Persistent runtime storage with isolated tests |
| Local build by default; optional Podman scripts | Fast local feedback without requiring container builds |
| Encrypt stored BAW credentials with AES-GCM | The per-installation secret is stored only in Git-ignored `data/db.env` locally or supplied via secure production configuration (see [ADR-011](../adrs/ADR-011-secrets-out-of-version-control.md)) |

Build and runtime flows are defined in `pom.xml`, `Containerfile`, and `scripts/`. The local build and test flows were run during this documentation update.
