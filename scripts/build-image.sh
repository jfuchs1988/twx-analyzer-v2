#!/usr/bin/env bash
# Explicit opt-in: package the locally built Quarkus application as a Podman image.
set -euo pipefail

if [ -n "${MSYSTEM:-}" ]; then
  export MSYS2_ARG_CONV_EXCL='*'
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi

if ! command -v podman >/dev/null 2>&1; then
  echo "ERROR: podman not found. This script is only for an explicitly requested image build." >&2
  exit 1
fi
if [ ! -f "$REPO/target/quarkus-app/quarkus-run.jar" ]; then
  echo "ERROR: target/quarkus-app is missing. Run scripts/build.sh locally first." >&2
  exit 1
fi

podman build -t twxav2-app:latest "$REPO"
