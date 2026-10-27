#!/usr/bin/env bash
# Host development mode: mvn quarkus:dev with live reload (backend changes take
# effect quickly without building an application container). This exception to
# the normal container workflow was explicitly approved on 2026-10-22.
#
# Requirements:
#   - Podman for the persistent MariaDB container and volume.
#   - JDK 25 and Maven 3.10.x; optionally set BPM_CA_FILE for BPM TLS.
#   - Port 8080 must be free; the frontend/API containers are stopped if running.
#
# Frontend: run `npm run dev` locally from frontend/ (Vite proxies to :8080).
#
# Usage: scripts/dev.sh [--skip-db] [Maven arguments, e.g. -Dquarkus.log.level=DEBUG]
set -euo pipefail

SKIP_DB="${TWXAV2_SKIP_DB:-0}"
MVN_ARGS=()
for arg in "$@"; do
  case "$arg" in
    --skip-db) SKIP_DB=1 ;;
    --help|-h)
      echo "Usage: scripts/dev.sh [--skip-db] [Maven arguments...]"
      echo "  --skip-db  Require an already-running Podman MariaDB; do not start/recreate it."
      exit 0
      ;;
    *) MVN_ARGS+=("$arg") ;;
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
# shellcheck source=scripts/podman-db.sh
source "$REPO/scripts/podman-db.sh"
DB_MODE=start
[ "$SKIP_DB" = "1" ] && DB_MODE=skip
twxav2_ensure_podman_db "$REPO" "$DB_MODE"
[ -n "${DB_SECRET:-}" ] || echo "WARNING: DB_SECRET is unset; BAW passwords use plaintext compatibility mode." >&2
[ -n "${BPM_DEFAULT_BASE_URL:-}" ] && export BPM_DEFAULT_BASE_URL

# Host tools (paths can be overridden through environment variables).
# Point JAVA_HOME to JDK 25; otherwise Maven may inherit an older JDK.
# Use java.home because the PATH java command may be a stub rather than a full JDK.
need_home=1
if [ -n "${JAVA_HOME:-}" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -qE 'version "25\.'; then
  need_home=0
fi
if [ "$need_home" = 1 ] && command -v java >/dev/null 2>&1; then
  JDK_HOME="$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^[[:space:]]*java.home = //p' | tr -d '\r')"
  JDK_HOME="$(cygpath -u "$JDK_HOME" 2>/dev/null || echo "$JDK_HOME")"
  [ -x "$JDK_HOME/bin/java" ] && export JAVA_HOME="$JDK_HOME"
fi
MVN="${MVN_BIN:-mvn}"
if ! command -v "$MVN" >/dev/null 2>&1 && [ ! -f "$MVN" ]; then
  echo "ERROR: Maven not found: $MVN (set MVN_BIN)." >&2
  exit 1
fi
twxav2_require_java_maven
MVN_SETTINGS="$(mktemp)"
printf '%s\n' '<settings xmlns="http://maven.apache.org/SETTINGS/1.2.0"></settings>' > "$MVN_SETTINGS"
trap 'rm -f "$MVN_SETTINGS"' EXIT

# Free port 8080 for the API development server (leave the database running).
if podman ps -f name=twxav2-frontend --format "{{.Names}}" | grep -qx twxav2-frontend; then
  echo "Stopping the frontend container to free port 8080..."
  podman stop twxav2-frontend >/dev/null
fi
if podman ps -f name=twxav2-app --format "{{.Names}}" | grep -qx twxav2-app; then
  echo "Stopping the app container to free port 8080 for development mode..."
  podman stop twxav2-app >/dev/null
fi

echo "Starting quarkus:dev (Ctrl+C to stop) — UI http://127.0.0.1:8080, Dev UI /q/dev"
"$MVN" -s "$MVN_SETTINGS" quarkus:dev \
  -Dquarkus.analytics.disabled=true \
  -Dquarkus.live-reload.instrumentation=true \
  -Dquarkus.http.host=127.0.0.1 \
  "${MVN_ARGS[@]}"
