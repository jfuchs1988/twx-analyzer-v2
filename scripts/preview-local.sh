#!/usr/bin/env bash
# Start Quarkus locally against the persistent Podman MariaDB database.
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
source "$REPO/scripts/podman-db.sh"
DB_MODE=start
[ "$SKIP_DB" = "1" ] && DB_MODE=skip
twxav2_ensure_podman_db "$REPO" "$DB_MODE"
[ -n "${DB_SECRET:-}" ] || echo "WARNING: DB_SECRET is unset; BAW passwords use plaintext compatibility mode." >&2
export UPLOAD_DIR="$TWXAV2_DATA_DIR/uploads"
export QUARKUS_PROFILE=local-preview

# Keep the database alive, but free the UI port if the containerized app is running.
if podman ps -f name=twxav2-app --format '{{.Names}}' | grep -qx twxav2-app; then
  echo "Stopping the app container to free port 8080; MariaDB remains running."
  podman stop twxav2-app >/dev/null
fi

MVN="${MVN_BIN:-mvn}"
echo "Starting the local preview at http://127.0.0.1:8080 (Ctrl+C to stop)."
echo "Uses Podman MariaDB with its datadir bind-mounted from $TWXAV2_DATA_DIR/mariadb."
"$MVN" -B -ntp \
  -Dquarkus.analytics.disabled=true \
  -Dquarkus.profile=local-preview \
  -Dquarkus.test.continuous-testing=disabled \
  -Dquarkus.http.host=127.0.0.1 \
  -Dquarkus.http.port=8080 \
  quarkus:dev
