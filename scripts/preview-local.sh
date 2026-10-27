#!/usr/bin/env bash
# Start the static Vite preview and Quarkus API separately against the persistent
# Podman MariaDB database. The UI/API same-origin proxy is provided by Vite.
set -euo pipefail

SKIP_DB="${TWXAV2_SKIP_DB:-0}"
for arg in "$@"; do
  case "$arg" in
    --skip-db) SKIP_DB=1 ;;
    --help|-h)
      echo "Usage: scripts/preview-local.sh [--skip-db]"
      echo "  --skip-db  Require an already-running Podman MariaDB; do not start/recreate it."
      exit 0
      ;;
    *) echo "ERROR: Unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [ -n "${MSYSTEM:-}" ]; then
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
cd "$REPO"
source "$REPO/scripts/local-toolchain.sh"
twxav2_use_local_toolchain "$REPO"
twxav2_require_java_maven
twxav2_require_node_npm
source "$REPO/scripts/podman-db.sh"
DB_MODE=start
[ "$SKIP_DB" = "1" ] && DB_MODE=skip
twxav2_ensure_podman_db "$REPO" "$DB_MODE"
[ -n "${DB_SECRET:-}" ] || echo "WARNING: DB_SECRET is unset; BAW passwords use plaintext compatibility mode." >&2
export UPLOAD_DIR="$TWXAV2_DATA_DIR/uploads"
export QUARKUS_PROFILE=local-preview

# Build the static UI into frontend/dist before starting the separate preview.
"$REPO/scripts/build-frontend.sh"

# Keep the database alive, but free the UI/API ports used by the split preview.
if podman ps -f name=twxav2-frontend --format '{{.Names}}' | grep -qx twxav2-frontend; then
  echo "Stopping the frontend container to free port 8080; MariaDB remains running."
  podman stop twxav2-frontend >/dev/null
fi
if podman ps -f name=twxav2-app --format '{{.Names}}' | grep -qx twxav2-app; then
  echo "Stopping the app container before starting the local API; MariaDB remains running."
  podman stop twxav2-app >/dev/null
fi

MVN="${MVN_BIN:-mvn}"
echo "Starting Quarkus API on :8081 and static frontend preview on :8080 (Ctrl+C to stop)."
echo "Uses Podman MariaDB with its datadir bind-mounted from $TWXAV2_DATA_DIR/mariadb."
"$MVN" -B -ntp \
  -Dquarkus.analytics.disabled=true \
  -Dquarkus.profile=local-preview \
  -Dquarkus.test.continuous-testing=disabled \
  -Dquarkus.http.host=127.0.0.1 \
  -Dquarkus.http.port=8081 \
  quarkus:dev &
BACKEND_PID=$!
cleanup() {
  kill "$BACKEND_PID" 2>/dev/null || true
  wait "$BACKEND_PID" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

cd "$REPO/frontend"
export TWXAV2_API_PROXY_TARGET=http://localhost:8081
npm run preview -- --host 127.0.0.1 --port 8080
