#!/usr/bin/env bash
# Start twxav2: Podman network + MariaDB + API + separate frontend (host :8080).
set -euo pipefail

SKIP_DB="${TWXAV2_SKIP_DB:-0}"
for arg in "$@"; do
  case "$arg" in
    --skip-db) SKIP_DB=1 ;;
    --help|-h)
      echo "Usage: scripts/run.sh [--skip-db]"
      echo "  --skip-db  Use an already-running Podman MariaDB without restarting it."
      exit 0
      ;;
    *) echo "ERROR: Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# Git Bash (MSYS) corrupts `/app:z` in `-v` arguments. Disable conversion and
# pass Windows paths to podman.exe instead.
if [ -n "${MSYSTEM:-}" ]; then
  export MSYS2_ARG_CONV_EXCL='*'
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
NET="twxav2"
DB="twxav2-db"
APP="twxav2-app"
FRONTEND="twxav2-frontend"

if ! command -v podman >/dev/null 2>&1; then
  echo "ERROR: podman not found." >&2
  exit 1
fi
for image in twxav2-app:latest twxav2-frontend:latest; do
  if ! podman image exists "$image"; then
    echo "ERROR: required image is missing: $image" >&2
    echo "Build the API and frontend images with scripts/build-image.sh and scripts/build-frontend-image.sh." >&2
    exit 1
  fi
done

# Shared database setup; MariaDB files, credentials, and uploaded TWX archives
# persist under the repository's Git-ignored data/ directory.
# shellcheck source=scripts/podman-db.sh
source "$REPO/scripts/podman-db.sh"
DB_MODE=start
[ "$SKIP_DB" = "1" ] && DB_MODE=skip
twxav2_ensure_podman_db "$REPO" "$DB_MODE"

# The packaged Quarkus application runs with the production profile, where
# plaintext storage is forbidden. DB_SECRET is loaded from the protected db.env.
if [ -z "${DB_SECRET:-}" ]; then
  echo "ERROR: DB_SECRET is required to run the production application." >&2
  echo "Restore it from the protected data/db.env or configure it through a secret manager." >&2
  exit 1
fi

# ---- Application ----
# Optional PEM trust anchor for direct BPM import; it can also be configured in
# the settings UI. Without a file, system certificate authorities are used.
CA_ARGS=()
if [ -n "${BPM_CA_FILE:-}" ]; then
  if [ ! -f "$BPM_CA_FILE" ]; then
    echo "ERROR: BPM_CA_FILE not found: $BPM_CA_FILE" >&2
    exit 1
  fi
  if [ -n "${MSYSTEM:-}" ]; then
    BPM_CA_FILE="$(cygpath -w "$BPM_CA_FILE")"
  fi
  CA_ARGS=(-v "$BPM_CA_FILE:/app/certs/custom-ca.pem:ro" -e BPM_CA_FILE=/app/certs/custom-ca.pem)
fi
# Pass optional environment overrides for transaction and BPM timeouts through.
EXTRA_ENV=()
[ -n "${TX_TIMEOUT:-}" ] && EXTRA_ENV+=(-e TX_TIMEOUT="$TX_TIMEOUT")
[ -n "${BPM_REQUEST_TIMEOUT:-}" ] && EXTRA_ENV+=(-e BPM_REQUEST_TIMEOUT="$BPM_REQUEST_TIMEOUT")
[ -n "${BPM_EXPORT_TIMEOUT:-}" ] && EXTRA_ENV+=(-e BPM_EXPORT_TIMEOUT="$BPM_EXPORT_TIMEOUT")
[ -n "${BPM_DEFAULT_BASE_URL:-}" ] && EXTRA_ENV+=(-e BPM_DEFAULT_BASE_URL="$BPM_DEFAULT_BASE_URL")
# Master secret for encrypting the stored BAW password (SecretsCrypto).
[ -n "${DB_SECRET:-}" ] && EXTRA_ENV+=(-e DB_SECRET="$DB_SECRET")
# Temporary old key for startup re-encryption; remove it after successful rotation.
[ -n "${DB_SECRET_PREVIOUS:-}" ] && EXTRA_ENV+=(-e DB_SECRET_PREVIOUS="$DB_SECRET_PREVIOUS")
podman rm -f "$FRONTEND" "$APP" >/dev/null 2>&1 || true
podman run -d --name "$APP" --network "$NET" \
  -e DB_HOST="$DB" -e DB_PORT=3306 -e DB_NAME=twxav2 \
  -e DB_USER=twxav2 -e DB_PASSWORD="$DB_PASSWORD" \
  -e UPLOAD_DIR=/app/data/uploads \
  -v "$TWXAV2_DATA_DIR_HOST/uploads:/app/data/uploads:z" \
  "${CA_ARGS[@]}" "${EXTRA_ENV[@]}" \
  twxav2-app:latest >/dev/null
podman run -d --name "$FRONTEND" --network "$NET" \
  -p 127.0.0.1:8080:8080 \
  -e API_UPSTREAM="$APP:8080" \
  twxav2-frontend:latest >/dev/null

echo
echo "twxav2 is running:"
echo "  UI:       http://127.0.0.1:8080"
echo "  API docs: http://127.0.0.1:8080/q/swagger-ui"
echo "  Logs:     podman logs -f $FRONTEND (API: $APP; DB: $DB)"
