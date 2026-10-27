# 10 — Quality Requirements

## Quality tree (inferred)

| Quality attribute | Refinement / visible implementation |
| --- | --- |
| Functional suitability | TWX parsing, rule-based analysis, search, comparison, toolkit analysis |
| Reliability | Ingest status, bounded workers, transaction timeout, per-rule fault isolation |
| Testability | JUnit/Quarkus tests and JaCoCo line coverage; Maven gate currently requires at least 64% (`pom.xml`) |
| Modifiability | Separate API, service, analysis, and UI packages; backend tests |
| Usability | UI dashboard, progress status, filters, and English/German support |
| Interoperability | REST/JSON, export formats, and optional BAW import |
| Security | BPM passwords encrypted when a secret is configured; optional token check for exports |

## Scenarios

1. **Large TWX import:** The job remains visible, runs with configurable concurrency, and may require a transaction timeout longer than 60 seconds.
2. **Faulty rule:** A rule failure does not block the rest of the analysis and is reported as an INFO finding.
3. **CI quality gate:** Findings are evaluated against configurable limits, and the export endpoint can be protected with an API token.
4. **BAW TLS:** An operator can provide an additional CA file for the BPM connection.

The source does not specify measurable SLAs, maximum concurrent users, expected TWX sizes, or formal acceptance criteria.
