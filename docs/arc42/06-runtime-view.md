# 6 — Runtime View

## Import a TWX file

1. The browser sends a file to the upload endpoint; the UI displays upload progress.
2. The server enqueues a job in `IngestQueue`. Duplicate pending jobs can be rejected using a deduplication key.
3. Workers store the file in the configured upload directory and parse its manifest, project objects, and embedded toolkits.
4. Automatic/manual exclusions and rule settings are applied; objects and variable usage are persisted.
5. `RuleEngine` runs enabled rules. A rule failure becomes an INFO finding instead of aborting the entire rule run.
6. The snapshot and findings are exposed to the UI, search, comparison, and export. Queue status remains available for a limited time.

## Direct BAW import

1. Test the configured connection or read the available containers and versions.
2. `BpmClient` authenticates with the Operations API, renews the session once after HTTP 401/403, and downloads the TWX export.
3. The download is size-limited, then follows the same ingest/analysis path as a file upload.

## Analysis and display

The UI requests snapshots and details through `/api/...`. Comparisons report added, removed, and changed objects plus finding deltas. API calls are listed in `frontend/src/api.ts`; their resource mappings are under `src/main/java/io/twxav2/api/`.
