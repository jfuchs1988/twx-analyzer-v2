# ADR-004 — MariaDB by Default; DB2 as a Possible Production Path

- **Date:** 2026-10 (transcribed from the decision record)
- **Status:** Accepted; DB2 path not yet enabled

## Context

Local/containerized installations need an easily available relational database. DB2 is a target for some production environments.

## Decision

- Default: MariaDB, currently configured with `quarkus.datasource.db-kind=mariadb`.
- Hibernate ORM manages the schema with `generation=update`; Flyway is not used.
- DB2 is a possible build/runtime path (`db-kind=db2`), but the DB2 driver and integration are not enabled in the current `pom.xml`.

## Notes for Future DB2 Enablement

- Treat `@Lob` fields as CLOBs.
- Use JPQL/Pageables for paging.
- Set the DB2 schema through configuration only.
- Keep entities in portable JPQL and avoid DB2-specific SQL assumptions.

## Consequences

- Schema changes can drift between installations; migrations need a separate strategy.
- Implement and verify DB2 compatibility before production use.
- A MariaDB major-version upgrade requires a backup and validation of the persistent data volume.
