# 8 — Crosscutting Concepts

## TWX domain model

A snapshot represents an imported export. It contains application objects and toolkits; objects are analyzed and findings are classified by rule ID and severity. Toolkit inventory and dependencies are also evaluated across snapshots.

Toolkit object XML is not retained in full. The parser keeps the metadata stubs needed for rule analysis, including environment-variable names so duplicate definitions across application/toolkit packages can be detected. Environment-variable defaults are deliberately omitted because they may contain secrets.

## Analysis and findings

Rules implement the existing rule contract and are invoked by `RuleEngine` in ID order. Severity, enablement, impact, and thresholds can be overridden globally or per snapshot. Results include object/location references, a message, and team status.

## Error and load behavior

Ingest jobs run with bounded concurrency. The transaction timeout is configurable through `TX_TIMEOUT`. Per-rule failures are represented as findings; parser, database, or ingest failures can still fail the job.

## Configuration and secrets

Configuration is in `application.properties` and can be overridden through MicroProfile Config/environment variables. First-time Podman setup creates a random `DB_SECRET` in Git-ignored `data/db.env`; protect and back up that file with `data/mariadb`. Production should use the established secure secret-management path. `SecretsCrypto` uses PBKDF2-HMAC-SHA256 and AES-256-GCM with a per-value salt and IV. Without `DB_SECRET`, values are stored as plaintext for compatibility. Losing or changing the secret can make existing encrypted values unreadable.

## Interfaces and localization

The frontend centralizes API contracts in `frontend/src/api.ts`. UI text and backend error messages support English and German. Swagger UI is always included according to application configuration.

## Security

The API token filter limits optional protection to `/api/export`. Query-string tokens can appear in URLs/logs; prefer the header. The visible code does not provide general authentication for other endpoints. TLS trust and BAW credentials are configurable; deploy behind appropriate access controls before exposing the service to a network.
