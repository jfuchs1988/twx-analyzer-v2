# ADR-011 — Keep Secrets Out of Version Control

- **Status:** Reconstructed from source; historical approval not verified
- **Date:** Original date unknown
- **Source:** Reconstructed from `SecretsCrypto.java`, `application.properties`, and `scripts/run.sh`

## Context

BAW credentials are stored in the database. Checking a master secret into source control, build artifacts, or container images would undermine the protection of stored credentials. Local runtime state is kept in the ignored `data/` directory, which is not committed.

## Decision (reconstructed from the implementation)

Supply the master secret through `DB_SECRET` (`twxav2.security.db-secret`); never commit it or store it in source code. First-time Podman setup generates a per-installation secret in Git-ignored `data/db.env`. Protect and back up that file with `data/mariadb`. Production startup requires `DB_SECRET`; non-production profiles retain plaintext compatibility. When configured, `SecretsCrypto` encrypts values with AES-256-GCM and derives the key using PBKDF2-HMAC-SHA256. Each value uses a randomly generated salt and IV. Existing plaintext values are encrypted transactionally at startup. Rotate a key by supplying the new `DB_SECRET` and old `DB_SECRET_PREVIOUS` for one startup, then remove the previous key after verifying the BPM connection. Keep database backups paired with their encryption key; ciphertext cannot recover a lost key.

## Consequences

- Production configuration must provide `DB_SECRET` securely and account for it in backup/restore procedures; startup fails if it is missing.
- Losing or incompatibly changing the secret makes encrypted values unreadable; backups and the documented rotation procedure mitigate accidental loss/change.
- Plaintext compatibility simplifies development migration but remains risky for non-production data.
- Database access and secret management must be secured in addition to this application component.

## Unverified details

The original alternatives, detailed trade-offs, decision owner, and decision date are not available.
