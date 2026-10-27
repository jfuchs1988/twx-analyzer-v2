# 11 — Risks and Technical Debt

| Risk / open question | Impact | Mitigation |
| --- | --- | --- |
| There is no application login by explicit deployment requirement; every client on a reachable network can modify data/settings | Exposure outside the closed trusted intranet would allow unauthorized reads and changes | Keep the service on the trusted intranet; supplied Podman scripts publish the UI on loopback and keep the API private. BAW Basic credentials are restricted to explicitly allowed HTTPS origins. |
| Non-production profiles may store secrets as plaintext; production refuses startup without `DB_SECRET` | The BAW password may be readable in non-production database files | Keep non-production data protected; production startup validation is tested |
| Losing the encryption key prevents decryption of existing values | BAW credentials must be re-entered unless a matching database/key backup exists | Back up database and key together; use the documented `DB_SECRET_PREVIOUS` startup rotation and recovery procedure |
| Reconstructed ADR-011 history | Rationale and alternatives may be incomplete | Retrieve the original decision or confirm the reconstruction |
| `NOTICE` contains only a short list of direct third-party components | Transitive dependencies and full license obligations may be missed | Review all Maven/npm dependency licenses before redistribution and include required notices |
| Large upload/export limits (500 MB HTTP body; 2 GB BAW export) | Resource or disk exhaustion under load | Set operational limits and monitor available storage |

This section records risks visible in the source and does not replace a security or license review.
