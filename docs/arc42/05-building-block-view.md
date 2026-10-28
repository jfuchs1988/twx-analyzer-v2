# 5 — Building Block View

## Level 1 — System

`twx-analyzer-v2` is a web system consisting of a Java backend, a React UI, relational storage, and file storage.

## Level 2 — Backend

| Building block | Responsibility | Examples |
| --- | --- | --- |
| REST API | UI and integration endpoints | `api/SnapshotResource`, `SearchResource`, `CompareResource`, `ExportResource`, `BpmResource` |
| Ingest | Accept and process TWX files | `service/IngestQueue`, `IngestService`, `ingest/TwxParser` |
| Analysis | Rules and TWX domain models | `analysis/RuleEngine`, `analysis/ref`, parser and rule packages |
| Persistence model | Store snapshots, findings, objects, and toolkits | `model/*`, Panache/JPA |
| Cross-cutting services | Settings, exclusions, variables, BPM, and secrets | `service/*` |

## Level 2 — Frontend

`frontend/src/app/` provides the application shell and navigation. The navigation groups process-app snapshots and toolkits under the localized Products menu. `frontend/src/features/` groups screens and feature-specific components by snapshots, toolkits, search, dashboard, comparison, settings, and import. `frontend/src/shared/` contains typed API calls, localization/design providers, and cross-feature reusable components. Vite builds this SPA into `frontend/dist`; a separate Nginx image serves it and proxies API/OpenAPI routes to Quarkus.

## Dependencies

The UI calls the backend REST API. API resources orchestrate services and persistence; ingest stores parse results, analyzes them, and updates snapshot/finding data. Maven dependencies include Quarkus REST/Jackson, Hibernate ORM Panache, MariaDB JDBC, SmallRye OpenAPI, and Mozilla Rhino; tests use Quarkus JUnit and H2. See `pom.xml` for details.
