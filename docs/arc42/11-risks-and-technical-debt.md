# 11 — Risks and Technical Debt

| Risk / open question | Impact | Mitigation |
| --- | --- | --- |
| API endpoints other than export lack general authentication; BPM settings accept a caller-supplied target URL while the client reuses stored Basic credentials and permits HTTP | A reachable unauthenticated API can expose data or redirect stored BAW credentials to a caller-selected host | Define authentication/authorization for all endpoints, require HTTPS, and constrain BAW egress to trusted targets |
| `DB_SECRET` is optional; storage is plaintext without it | The BAW password may be readable in the database | Require it in production; test secure secret management, rotation, and backups |
| Secret loss/change prevents decryption of existing values | BAW credentials must be re-entered; no re-key procedure is documented | Implement and document rotation and recovery |
| CSV export quotes cells but does not neutralize spreadsheet formula prefixes | Imported object fields or status comments may execute as formulas when opened in spreadsheet software | Escape formula-leading values and add export regression tests |
| Upload/body and per-entry limits do not bound total parsed content or queued work | Large or numerous imports can consume excessive heap, CPU, or temporary disk space | Enforce aggregate expanded-size/entry limits, queue backpressure, storage quotas, and restart cleanup |
| Search loads all matching object entities before applying its hit limit and time budget | Large snapshots can produce long queries and high memory use despite the advertised search budget | Fetch candidates in bounded pages and check the budget between pages |
| Toolkit graph/conflict views perform repeated per-node and per-snapshot queries | Large inventories can increase request latency and database load | Profile representative inventories and batch graph/inventory reads |
| Reconstructed ADR-011 history | Rationale and alternatives may be incomplete | Retrieve the original decision or confirm the reconstruction |
| `NOTICE` contains only a short list of direct third-party components | Transitive dependencies and full license obligations may be missed | Review all Maven/npm dependency licenses before redistribution and include required notices |
| Large upload/export limits (500 MB HTTP body; 2 GB BAW export) | Resource or disk exhaustion under load | Set operational limits and monitor available storage |

This section records risks visible in the source and does not replace a security or license review.
