#!/usr/bin/env bash
# Build the independently deployed static frontend image.
set -euo pipefail

if [ -n "${MSYSTEM:-}" ]; then
  export MSYS2_ARG_CONV_EXCL='*'
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi

if ! command -v podman >/dev/null 2>&1; then
  echo "ERROR: podman not found. This script builds the optional frontend image." >&2
  exit 1
fi
if [ ! -f "$REPO/frontend/dist/index.html" ]; then
  echo "ERROR: frontend/dist is missing. Run scripts/build-frontend.sh first." >&2
  exit 1
fi

podman build -f "$REPO/Containerfile.frontend" -t twxav2-frontend:latest "$REPO"
echo "Done: twxav2-frontend:latest"
