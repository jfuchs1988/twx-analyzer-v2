# Documentation

Project documentation is living documentation: update the relevant page in the same change as the implementation, configuration, or workflow it describes. Keep descriptions aligned with the current source tree; date version checks and other time-sensitive claims. Do not defer documentation updates to a later cleanup.

Never document or reference developer-local test data in repository docs or code. Keep test data synthetic or sanitized and maintained with the test suite; do not expose local paths, filenames, contents, or identifying metadata.

- [`arc42/README.md`](arc42/README.md): architecture documentation organized into twelve sections.
- [`adrs/README.md`](adrs/README.md): architecture decisions and reconstruction status.
- [`NOTICE-AUDIT.md`](NOTICE-AUDIT.md): upstream code comparison and attribution locations.
- [`THIRD-PARTY-NOTICES.md`](THIRD-PARTY-NOTICES.md): resolved Maven/npm dependency license inventory and distribution notices.
- [`build-versions.md`](build-versions.md): reviewed build/runtime versions and documented exceptions.

This documentation describes the state visible in the source tree. ADR-001 through ADR-010 record the supplied architecture decisions; ADR-011 records the secret-encryption decision reconstructed from the current implementation; ADR-012 records the accepted frontend modularity/deployment choice. License and provenance information is in the root `LICENSE` and `NOTICE` files.

`WORKLOG.md` is a short-lived handoff for active work and unresolved tasks, not a project history. Remove completed items after updating any canonical documentation they affect.
