#!/usr/bin/env bash
# Build the frontend locally into frontend/dist for independent deployment.
set -euo pipefail

if [ -n "${MSYSTEM:-}" ]; then
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
source "$REPO/scripts/local-toolchain.sh"
twxav2_use_local_toolchain "$REPO"
twxav2_require_node_npm
echo "== Frontend build =="
if ! command -v npm >/dev/null 2>&1; then
  echo "ERROR: npm not found. Node.js 24.22.1 and npm 12.2.1 are required." >&2
  exit 1
fi
cd "$REPO/frontend"
if [ ! -f node_modules/.package-lock.json ] \
    || [ package.json -nt node_modules/.package-lock.json ] \
    || [ package-lock.json -nt node_modules/.package-lock.json ]; then
  npm ci --no-audit --no-fund
fi
npm run build
echo "Done: frontend/dist updated."
