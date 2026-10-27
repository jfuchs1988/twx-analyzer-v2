# 1 — Introduction and Goals

## Requirements Overview

TWX Analyzer v2 accepts exports from IBM Business Automation Workflow (BAW) / Process Designer, extracts project and toolkit content, and analyzes it with a rule catalog. Results are persisted and exposed through a web UI and REST endpoints.

## Quality Goals

Goals inferred from the visible behavior; no formal priority order has been confirmed:

1. **Understandable quality results:** Show findings with rule, severity, object reference, and status.
2. **Comparability:** Compare project snapshots and individual objects.
3. **Operational usability:** Support large imports with a queue, progress phases, and persisted history.
4. **Extensibility:** Organize rules and parsers by BAW object type.
5. **Integration:** Make data and quality-gate results available through APIs and exports.

## Stakeholders

| Role | Interest |
| --- | --- |
| BAW developers and architects | Findings, object/version comparison, rule configuration |
| Team and quality leads | Trends, status tracking, and quality gates |
| CI/automation systems | Import/analysis results and exports |
| Operators | Database, uploads, secrets, BAW connectivity, and availability |

## Scope

The visible scope includes file upload, analysis, search, dashboards, toolkits, object views, rule configuration, optional direct BAW import, and finding exports. Installation, user management, and authentication beyond the optional export token are not established as product features.
