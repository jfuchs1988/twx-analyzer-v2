#!/usr/bin/env bash
# Start only the persistent Podman MariaDB service, leaving it running for local work.
set -euo pipefail

if [ -n "${MSYSTEM:-}" ]; then
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
source "$REPO/scripts/podman-db.sh"
twxav2_ensure_podman_db "$REPO" start
echo "MariaDB is running on 127.0.0.1:8081. Database files: $TWXAV2_DATA_DIR/mariadb"
echo "Uploads and db.env: $TWXAV2_DATA_DIR"
