# 3 — Context and Scope

## Business Context

```text
BAW / Process Designer user ── TWX file ──┐
BAW server ── read-only Operations REST API ─┼──> TWX Analyzer v2
CI/automation ── export API + optional token ┘        │
                                                     ├── Web UI
                                                     ├── MariaDB (default)
                                                     └── Uploaded files
```

## External Interfaces

| External party | Exchange |
| --- | --- |
| Web browser | UI and JSON REST endpoints under `/api/...`, served from the same origin |
| BAW Operations API | Read container/version information, authenticate to obtain a CSRF token, and download exports |
| MariaDB | Persist snapshots, objects, findings, rules, toolkit data, and BPM settings |
| Filesystem/volume | Temporary imports and persisted upload files |
| CI/automation | JSON/CSV/HTML from `/api/export/*`; CSV quotes delimiters and prefixes formula-leading text cells with an apostrophe; optional `X-API-Token` header or query token |

## Scope and Security Note

According to `BpmClient`, BAW data operations are read-only; the login POST obtains a CSRF token. This application does not replace BAW or Process Designer. Application login is intentionally not provided: the deployment contract is a closed, trusted intranet. All API routes are therefore available to clients that can reach the service; keep them behind the intranet network boundary. The supplied Podman stack binds the UI to loopback and does not publish the API container. The optional API token protects exports only. BAW credentials are sent only to HTTPS origins explicitly allowed by `BPM_ALLOWED_ORIGINS`, or to the origin in `BPM_DEFAULT_BASE_URL` when no explicit allowlist is configured.
