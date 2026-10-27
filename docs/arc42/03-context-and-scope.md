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
| CI/automation | JSON/CSV/HTML from `/api/export/*`; optional `X-API-Token` header or query token |

## Scope and Security Note

According to `BpmClient`, BAW data operations are read-only; the login POST obtains a CSRF token. This application does not replace BAW or Process Designer. In the current code, only export endpoints can be optionally token-protected; other endpoints remain open according to `ApiTokenFilter`. Comprehensive authentication/authorization is not present and must be assessed before network exposure.
