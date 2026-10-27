# ADR-002: Java 25 for Build and Runtime

- **Date:** 2026-10 (transcribed from the decision record)
- **Status:** Accepted

## Context

The runtime should use a long-term-supported Java release; build and runtime must support the same language version.

## Decision

Use Java 25 (LTS): `maven.compiler.release=25`, local builds use JDK 25, and the optional runtime image uses Temurin 25 JRE. Maven 3.10.0 is the build runner.

## Rationale

Java 25 is the selected LTS line and is supported by the Quarkus version in use.

## Consequences

- Builds and production require Java 25.
- Keep patch releases within the LTS line current.
