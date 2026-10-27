# ADR-008 — Internal CA for Build Containers

- **Date:** 2026-10-20 (transcribed from the decision record)
- **Status:** Historically accepted; not implemented in the current build scripts

## Context

In a corporate environment, internal certificate authorities may be required for Maven and npm clients to reach package registries over TLS.

## Recorded Decision

The PEM CA bundle was to be stored at `tools/certs/internal-ca-chain.pem` and mounted into both build containers at `/certs/internal-ca-chain.pem`. Node was to use `NODE_EXTRA_CA_CERTS`; for Maven, each certificate was to be imported from the bundle into the Temurin JDK trust store with `keytool -importcert -cacerts`. `update-ca-certificates` was intended as a best-effort addition for other TLS tools.

## Current Status

The current `scripts/build.sh`, `scripts/build-frontend.sh`, and `scripts/test.sh` do not mount such a CA bundle and use public Maven Central/npm registries. The build CA mechanism is therefore not an active prerequisite in this checkout. A separate BPM CA can be configured for runtime imports in `scripts/run.sh`.

## Consequences

If builds again run behind internal TLS inspection, reactivate this decision and test certificate validation. Do not bypass TLS verification with a “trust all” configuration.
