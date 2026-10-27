#!/usr/bin/env bash
# Stop the frontend/API containers and, unless --keep-db is given, the database/network.
# The repository data/ directory is always preserved.
set -euo pipefail

KEEP_DB="${TWXAV2_KEEP_DB:-0}"
for arg in "$@"; do
  case "$arg" in
    --keep-db) KEEP_DB=1 ;;
    --help|-h)
      echo "Usage: scripts/stop.sh [--keep-db]"
      echo "  --keep-db  Stop only the app container; leave MariaDB running for local work."
      exit 0
      ;;
    *) echo "ERROR: Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

podman rm -f twxav2-frontend twxav2-app 2>/dev/null || true
if [ "$KEEP_DB" = "1" ]; then
  echo "Frontend and API stopped. MariaDB remains running on 127.0.0.1:8081."
  exit 0
fi
podman rm -f twxav2-db 2>/dev/null || true
podman network exists twxav2 && podman network rm twxav2 || true
echo "twxav2 stopped. Database files, uploads, and credentials remain under repository data/."
