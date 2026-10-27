# ADR-010 — Read-Only Direct BAW Import Through the Operations REST API

- **Date:** 2026-10-20 (transcribed from the decision record)
- **Status:** Accepted

## Context

TWX files should be importable directly from a BAW instance as well as by upload. Data operations must remain read-only; the Operations API login flow still requires a POST to obtain a session cookie and CSRF token.

## Decision

- List containers and versions through the BAW Operations REST API, download the TWX export, and pass it to the same `IngestService` used for uploads.
- Data endpoints are GET `/ops/std/bpm/containers`, GET `/ops/std/bpm/containers/{acronym}/versions`, and GET `/ops/std/bpm/containers/{acronym}/versions/{version}/export`.
- Data access uses GET. POST `/ops/system/login` is used only for authentication and token acquisition; no BAW business data is changed.
- Authentication uses HTTP Basic Auth and obtains a CSRF token and session cookie. Each GET sends `BPMCSRFToken`; the session is renewed once after HTTP 401/403.
- `BpmClient` uses `java.net.http.HttpClient` and limits export downloads to 2 GiB.
- Manage credentials through BPM settings; the API never returns the password. The application encrypts the stored password when `DB_SECRET` is set.
- TLS uses system CAs or an optional CA file. BAW connection/export timeouts are configurable.
- The current export request sets `use_enhanced_filenames=false` and limits downloads to 2 GiB.

## Rationale

- The Operations API provides the required container/version information and TWX exports.
- Reusing the ingest pipeline keeps analysis behavior consistent between upload and direct import.
- Restricting data operations to read-only endpoints limits the impact of the BAW account.

## Consequences

- The integration requires BAW connectivity and an account with appropriate read permissions.
- It depends on the behavior and version of the BAW Operations API.
- Configure TLS CAs and timeouts for each environment.
- Set the secret in production; see ADR-011.
