# ADR-008 — Internal CA for Build Containers

- **Date:** 2026-10-20 (transcribed from the decision record)
- **Status:** Not adopted (decision recorded 2026-10-27)

## Context

In a corporate environment, internal certificate authorities may be required for Maven and npm clients to reach package registries over TLS.

## Recorded Decision

The PEM CA bundle was to be stored at `tools/certs/internal-ca-chain.pem` and mounted into both build containers at `/certs/internal-ca-chain.pem`. Node was to use `NODE_EXTRA_CA_CERTS`; for Maven, each certificate was to be imported from the bundle into the Temurin JDK trust store with `keytool -importcert -cacerts`. `update-ca-certificates` was intended as a best-effort addition for other TLS tools.

## Current Decision

The historical proposal is not adopted for this release. The checked-in build uses the public Maven Central and npm registries, and no internal registry or approved corporate CA bundle is part of the required environment. Keep normal TLS certificate verification enabled. If builds later need internal registries, introduce an explicitly provisioned CA through secure configuration and test it; never add a trust-all fallback.

## Historical Implementation Status

The current `scripts/build.sh`, `scripts/build-frontend.sh`, and `scripts/test.sh` do not mount such a CA bundle and use public Maven Central/npm registries. The build CA mechanism is therefore not an active prerequisite in this checkout. A separate BPM CA can be configured for runtime imports in `scripts/run.sh`.

## Consequences

This ADR does not configure the separate runtime BAW CA (`BPM_CA_FILE`), which remains supported.
