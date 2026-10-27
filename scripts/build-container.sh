#!/usr/bin/env bash
# Full build inside containers; invoke explicitly (no host Maven/Node installation required).
# Requires Podman and network access to Maven Central and npmjs.org.
set -euo pipefail

# Git Bash (MSYS) corrupts `/app:z` in `-v` arguments. Disable conversion and
# pass Windows paths to podman.exe instead.
if [ -n "${MSYSTEM:-}" ]; then
  export MSYS2_ARG_CONV_EXCL='*'
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
MVN_IMAGE="docker.io/library/maven:3.10.0-eclipse-temurin-25"
NODE_IMAGE="docker.io/library/node:24.22.1-alpine"
# ---- 1) Frontend (Vite output to src/main/resources/META-INF/resources) ----
# Quarkus packages only resources present before Maven starts.
mkdir -p "$REPO/src/main/resources/META-INF/resources"
# SKIP_FRONTEND=1 skips this step when a previous UI build is already present
# in META-INF/resources (faster backend iterations; default: full build).
if [ "${SKIP_FRONTEND:-0}" = "1" ] && [ -f "$REPO/src/main/resources/META-INF/resources/index.html" ]; then
  echo "== Skipping frontend build (SKIP_FRONTEND=1) =="
else
  echo "== Frontend build =="
  # Mount the full repository so outDir ../src/main/resources/META-INF/resources
  # writes to the host rather than being lost inside the container.
  podman run --rm \
  -v "$REPO":/src:z -w /src/frontend \
  "$NODE_IMAGE" \
   sh -c "npm install --global npm@12.2.1 --no-audit --no-fund \
          && npm ci --no-audit --no-fund \
          && npm run build"
fi

# ---- 2) Backend (Maven/Quarkus build) ----
echo "== Backend build =="
podman run --rm \
  -v twxav2-m2:/root/.m2 \
  -v "$REPO":/src:z -w /src \
  "$MVN_IMAGE" \
  sh -c "printf '%s\\n' '<settings xmlns=\"http://maven.apache.org/SETTINGS/1.2.0\"></settings>' > /tmp/public-settings.xml \
         && mvn -s /tmp/public-settings.xml -q -U -DskipTests package"

# ---- 3) Application image ----
echo "== Application image =="
podman build -t twxav2-app:latest "$REPO"

echo "Done: twxav2-app:latest"
