# 8 — Crosscutting Concepts

## TWX domain model

A snapshot represents an imported export. It contains application objects and toolkits; objects are analyzed and findings are classified by rule ID and severity. Toolkit inventory and dependencies are also evaluated across snapshots.

Toolkit object XML is not retained in full. The parser keeps the metadata stubs needed for rule analysis, including environment-variable names so duplicate definitions across application/toolkit packages can be detected. Environment-variable defaults are deliberately omitted because they may contain secrets.

## Analysis and findings

Rules implement the existing rule contract and are invoked by `RuleEngine` in ID order. Severity, enablement, impact, and thresholds can be overridden globally or per snapshot. Results include object/location references, a message, and team status.

## Error and load behavior

Ingest jobs use a bounded worker pool, bounded waiting queue, and byte reservations for active/waiting work. HTTP connections and individual request bodies are capped; multipart uploads are written to a dedicated temporary directory and deleted after each request. Application staging files share the upload filesystem; abandoned multipart and staging files are removed on startup. Parser budgets cap archive size, entry/object counts, total expanded content, retained text, and embedded toolkits. The parser closes ZIP entry streams. The transaction timeout is configurable through `TX_TIMEOUT`. Search reads candidates in stable-ID pages of 32, checks the request budget between pages/objects, and clears each processed persistence-context page. A synthetic 4,096-object Quarkus regression test checks that a late match is returned within the 15-second maximum search budget. Per-rule failures are represented as findings; parser, database, or ingest failures can still fail the job.

## Configuration and secrets

Configuration is in `application.properties` and can be overridden through MicroProfile Config/environment variables. First-time Podman setup creates a random `DB_SECRET` in Git-ignored `data/db.env`; protect and back up that file with `data/mariadb`. The production profile fails at startup without `DB_SECRET`; non-production profiles retain plaintext compatibility, and existing plaintext BAW passwords are encrypted transactionally at startup when a key is present. `SecretsCrypto` uses PBKDF2-HMAC-SHA256 and AES-256-GCM with a per-value salt and IV. Rotate by supplying the new `DB_SECRET` and old `DB_SECRET_PREVIOUS` for one startup; successful startup re-encrypts the stored password transactionally. Remove the previous key after verifying the BPM connection. Keep a database backup paired with its key: a lost key cannot be recovered from ciphertext, but restoring the paired backup/key or re-entering the BAW password recovers service.

## Interfaces and localization

The frontend centralizes typed API calls in `frontend/src/shared/api.ts`. UI text and backend error messages support English and German. Swagger UI is always included according to application configuration and is exposed through the frontend reverse proxy.

## Security

There is intentionally no application login. The deployment requirement is a closed, trusted intranet; every reachable client can read and modify analyzer data and settings. The provided container stack binds the UI to loopback and keeps the API on its private Podman network; any alternate intranet publication must be limited by network ACLs to the trusted segment. The optional API token protects `/api/export` only; prefer its header over query parameters. BAW credentials are sent only over HTTPS to an explicitly allowed origin, redirects are disabled, and normal or configured PEM trust validation remains enabled.
