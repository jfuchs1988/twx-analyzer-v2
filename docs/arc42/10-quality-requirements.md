# 10 — Quality Requirements

## Quality tree (inferred)

| Quality attribute | Refinement / visible implementation |
| --- | --- |
| Functional suitability | TWX parsing, rule-based analysis, search, comparison, toolkit analysis |
| Reliability | Ingest status, bounded workers/queue/byte reservations, archive parse budgets, restart staging cleanup, transaction timeout, per-rule fault isolation |
| Testability | Plain JUnit coverage for instrumented code, Quarkus endpoint/persistence behavior tests, frontend component tests; JaCoCo gate currently requires at least 64% of instrumented lines (`pom.xml`) |
| Modifiability | Separate API, service, analysis, and UI packages; backend tests |
| Usability | UI dashboard, progress status, filters, and English/German support |
| Interoperability | REST/JSON, export formats, and optional BAW import |
| Security | Production requires `DB_SECRET` and encrypts stored BPM passwords; optional token check for exports; no login by deployment requirement, so network access must remain inside the trusted intranet; BAW credentials require an allowed HTTPS origin |

## Coverage strategy

`mvn test` is the backend test gate. Plain JUnit tests exercise deterministic services and analysis logic under JaCoCo instrumentation. `@QuarkusTest` tests exercise REST endpoints, persistence, and runtime wiring through HTTP and assert externally visible responses and database effects; Quarkus-managed classes loaded by `QuarkusClassLoader` may not be included in the JaCoCo report. Therefore, the 64% JaCoCo line threshold is a minimum for instrumented classes only, not whole-application or API coverage, and must not be presented as such.

For each API behavior change, add a focused Quarkus HTTP regression test for success and relevant validation/error behavior; add persistence assertions where state changes. Keep pure branch logic in directly testable methods where practical. Frontend user-critical settings, upload, and finding-status flows have separate Vitest/Testing Library component tests (`cd frontend && npm test`). If quantitative coverage of Quarkus-managed API classes becomes a release requirement, first establish a packaged-JVM `@QuarkusIntegrationTest` path with the JaCoCo agent and validate its report; do not lower/raise the existing threshold as a substitute.

## Scenarios

1. **Large TWX import:** The job remains visible, reserves from bounded active/queued work capacity, and rejects archives that exceed configured parser budgets. It may require a transaction timeout longer than 60 seconds.
2. **Faulty rule:** A rule failure does not block the rest of the analysis and is reported as an INFO finding.
3. **CI quality gate:** Findings are evaluated against configurable limits, and the export endpoint can be protected with an API token.
4. **BAW TLS:** An operator can provide an additional CA file for the BPM connection.
5. **Large toolkit inventory:** The cross-app conflict view reads roots and nested edges in batches; a synthetic multi-app test asserts the SQL statement count remains bounded as apps share a nested toolkit graph.

The source does not specify measurable SLAs, maximum concurrent users, expected TWX sizes, or formal acceptance criteria.
