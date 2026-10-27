# 6 — Runtime View

## Import a TWX file

1. The browser sends a file to the upload endpoint; the UI displays upload progress.
2. The server reserves bounded queue and staged-byte capacity before copying the file into its application staging directory. Duplicate pending jobs receive 409; exhausted capacity receives 429.
3. Workers move the file into the configured upload directory and parse it under configured archive, entry, object, expansion, retained-content, and toolkit budgets.
4. Automatic/manual exclusions and rule settings are applied; objects and variable usage are persisted.
5. `RuleEngine` runs enabled rules. A rule failure becomes an INFO finding instead of aborting the entire rule run.
6. The snapshot and findings are exposed to the UI, search, comparison, and export. Queue status remains available for a limited time. Abandoned staging files are removed on startup; staged files are deleted after completion or when queued jobs are rejected during shutdown.

## Direct BAW import

1. Test the configured connection or read the available containers and versions.
2. Before sending stored credentials, the server validates that the configured target is an allowed HTTPS origin. `BpmClient` authenticates with the Operations API, renews the session once after HTTP 401/403, and downloads the TWX export.
3. The download is size-limited and reserves the configured maximum export budget, then follows the same ingest/analysis path as a file upload.

## Analysis and display

The UI requests snapshots and details through `/api/...`, which the frontend reverse proxy forwards to Quarkus. Comparisons report added, removed, and changed objects plus finding deltas. Typed calls are listed in `frontend/src/shared/api.ts`; their resource mappings are under `src/main/java/io/twxav2/api/`.
