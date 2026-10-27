# ADR-011 — Keep Secrets Out of Version Control

- **Status:** Reconstructed from source; historical approval not verified
- **Date:** Original date unknown
- **Source:** Reconstructed from `SecretsCrypto.java`, `application.properties`, and `scripts/run.sh`

## Context

BAW credentials are stored in the database. Checking a master secret into source control, build artifacts, or container images would undermine the protection of stored credentials. Local runtime state is kept in the ignored `data/` directory, which is not committed.

## Decision (reconstructed from the implementation)

Supply the master secret through `DB_SECRET` (`twxav2.security.db-secret`); never commit it or store it in source code. First-time Podman setup generates a per-installation secret in Git-ignored `data/db.env`. Protect and back up that file with `data/mariadb`. Production deployments should use their secure secret-management path. When configured, `SecretsCrypto` encrypts values with AES-256-GCM and derives the key using PBKDF2-HMAC-SHA256. Each value uses a randomly generated salt and IV. Existing plaintext values are passed through when read. Without a secret, plaintext storage remains possible for compatibility.

## Consequences

- Production configuration must provide `DB_SECRET` securely and account for it in backup/restore procedures.
- Losing or incompatibly changing the secret makes encrypted values unreadable; a rotation/re-key procedure is not documented.
- Plaintext compatibility simplifies migration but is risky for production data.
- Database access and secret management must be secured in addition to this application component.

## Unverified details

The original alternatives, detailed trade-offs, decision owner, and decision date are not available.
