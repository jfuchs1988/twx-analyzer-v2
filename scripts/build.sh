#!/usr/bin/env bash
# Default local build: build the frontend and Quarkus package directly on the host.
set -euo pipefail

if [ -n "${MSYSTEM:-}" ]; then
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
cd "$REPO"
source "$REPO/scripts/local-toolchain.sh"
twxav2_use_local_toolchain "$REPO"
twxav2_require_node_npm
twxav2_require_java_maven

MVN="${MVN_BIN:-mvn}"
if ! command -v "$MVN" >/dev/null 2>&1 && [ ! -f "$MVN" ]; then
  echo "ERROR: Maven not found: $MVN (set MVN_BIN). Maven 3.10.0 and JDK 25 are required." >&2
  exit 1
fi
if ! command -v npm >/dev/null 2>&1; then
  echo "ERROR: npm not found. Node.js 24.22.1 and npm 12.2.1 are required." >&2
  exit 1
fi

echo "== Local frontend build =="
if [ "${SKIP_FRONTEND:-0}" = "1" ] && [ -f "$REPO/src/main/resources/META-INF/resources/index.html" ]; then
  echo "Skipping frontend build (SKIP_FRONTEND=1)"
else
  (
    cd "$REPO/frontend"
    if [ ! -f node_modules/.package-lock.json ] \
        || [ package.json -nt node_modules/.package-lock.json ] \
        || [ package-lock.json -nt node_modules/.package-lock.json ]; then
      npm ci --no-audit --no-fund
    fi
    npm run build
  )
fi

echo "== Local backend build =="
"$MVN" -B -ntp -DskipTests package

echo "Done: target/quarkus-app (no container image built)."
