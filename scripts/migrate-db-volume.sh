#!/usr/bin/env bash
# Copy the legacy Podman database volume into the repository's ignored data/ directory.
set -euo pipefail

if [ -n "${MSYSTEM:-}" ]; then
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
source "$REPO/scripts/podman-db.sh"
twxav2_require_podman
source "$REPO/scripts/data-dir.sh"
twxav2_init_data_dir "$REPO"

DB="twxav2-db"
VOLUME="twxav2-dbdata"
IMAGE="${MARIADB_IMAGE:-docker.io/library/mariadb:11.8.9}"

if podman ps -f "name=$DB" --format '{{.Names}}' | grep -qx "$DB"; then
  echo "ERROR: Stop MariaDB first; the volume must not be copied while the database is running." >&2
  exit 1
fi
if ! podman volume exists "$VOLUME"; then
  echo "No legacy Podman volume '$VOLUME' exists; no migration is needed."
  exit 0
fi
if [ -n "$(find "$TWXAV2_DATA_DIR/mariadb" -mindepth 1 -print -quit 2>/dev/null)" ]; then
  echo "ERROR: $TWXAV2_DATA_DIR/mariadb is not empty; refusing to overwrite it." >&2
  exit 1
fi

podman image exists "$IMAGE" || podman pull "$IMAGE"
podman run --rm --network none --user 0 \
  -v "$VOLUME:/from:ro" \
  -v "$TWXAV2_DATA_DIR_HOST/mariadb:/to:z" \
  --entrypoint sh "$IMAGE" -c 'cp -a /from/. /to/'

echo "Database files copied to $TWXAV2_DATA_DIR/mariadb."
echo "The original Podman volume '$VOLUME' is retained as a rollback copy."
echo "Start and verify MariaDB with scripts/start-db.sh; then remove the old volume with 'podman volume rm $VOLUME' if the copy is sound."
