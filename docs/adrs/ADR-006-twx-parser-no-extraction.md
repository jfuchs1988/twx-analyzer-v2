# ADR-006 — Parse TWX Files Without Extracting Them to Disk

- **Date:** 2026-10 (transcribed and updated to match the current implementation)
- **Status:** Accepted

## Context

TWX files are ZIP archives containing XML, payloads, toolkits, and binary files. Extracting the entire archive would require additional disk space and introduce path traversal (Zip Slip) risk.

## Decision

`TwxParser` reads ZIP entries through `ZipFile`/streams without extracting the archive structure to disk. It selectively processes manifest data and XML objects and skips binary files. The implementation limits object content to 4 MiB, embedded toolkit ZIPs to 256 MiB, and toolkit manifests to 8 MiB.

## Rationale

- Avoid extracting entries to filesystem paths, eliminating the Zip Slip attack surface.
- Apply explicit size limits to individual content and embedded archives.

## Consequences

- ZIP entries are accessed through `ZipFile`; the parser does not perform a single-pass stream over the entire archive.
- Size limits can prevent complete analysis of very large content.
- Per-file limits do not automatically bound total memory use for archives with many entries.
