# ADR-007: CDI Rule SPI Instead of a Fixed Rule Table

- **Date:** 2026-10 (transcribed from the decision record)
- **Status:** Accepted

## Context

The rule catalog is growing and requires different analysis logic, context access, configurable severity, and isolated tests.

## Decision

Rules implement `TwxRule` and are registered as CDI beans. `RuleEngine` injects `Instance<TwxRule>`, sorts rules by ID, and runs them with a shared `RuleContext`. The current reference catalog registers rules through CDI producers.

## Rationale

- Adding a rule does not require changing a hard-coded branch in the engine core.
- Rules can be tested individually and enabled/configured through settings.
- Rule failures can be isolated.

## Consequences

- Rule IDs must remain unique and stable.
- Registered beans and catalog tests must be updated when rules change.
