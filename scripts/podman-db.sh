#!/usr/bin/env bash
# Shared Podman MariaDB setup for the app container and local Quarkus preview.

twxav2_require_podman() {
  if ! command -v podman >/dev/null 2>&1 && [ -n "${MSYSTEM:-}" ] && [ -n "${LOCALAPPDATA:-}" ]; then
    local podman_dir
    podman_dir="$(cygpath -u "$LOCALAPPDATA")/Programs/Podman"
    if [ -f "$podman_dir/podman.exe" ]; then
      export PATH="$podman_dir:$PATH"
    fi
  fi
  if ! command -v podman >/dev/null 2>&1; then
    echo "ERROR: Podman is required for the persistent local database." >&2
    return 1
  fi
  if [ -n "${MSYSTEM:-}" ]; then
    export MSYS2_ARG_CONV_EXCL='*'
  fi
}

twxav2_ensure_podman_db() {
  local repo="$1"
  local mode="${2:-start}"
  local network="twxav2"
  local db="twxav2-db"
  local image="${MARIADB_IMAGE:-docker.io/library/mariadb:12.3.3}"
  local running=false
  local existing_data=false
  local storage_label=""

  twxav2_require_podman

  # shellcheck source=scripts/data-dir.sh
  source "$repo/scripts/data-dir.sh"
  twxav2_init_data_dir "$repo"
  case "$mode" in
    start|skip) ;;
    *) echo "ERROR: database mode must be 'start' or 'skip'." >&2; return 2 ;;
  esac
  if podman volume exists twxav2-dbdata || podman container exists "$db"; then
    existing_data=true
  fi
  if podman volume exists twxav2-dbdata \
      && [ -z "$(find "$TWXAV2_DATA_DIR/mariadb" -mindepth 1 -print -quit 2>/dev/null)" ]; then
    echo "ERROR: Legacy Podman volume twxav2-dbdata still holds the database." >&2
    echo "Stop MariaDB and migrate it with scripts/migrate-db-volume.sh before starting the repository-backed database." >&2
    return 1
  fi
  twxav2_load_db_env "$mode" "$existing_data"

  export DB_HOST=127.0.0.1 DB_PORT=8081 DB_NAME=twxav2 DB_USER=twxav2
  if podman ps -f "name=$db" --format '{{.Names}}' | grep -qx "$db"; then
    storage_label="$(podman inspect --format '{{index .Config.Labels "io.twxav2.storage"}}' "$db" 2>/dev/null || true)"
    if [ "$storage_label" != "repository-data" ]; then
      echo "ERROR: The running database container still uses legacy storage; migrate it before using this preview." >&2
      return 1
    fi
    if ! podman port "$db" 3306/tcp 2>/dev/null | grep -q '8081'; then
      echo "ERROR: The running database container does not publish host port 127.0.0.1:8081." >&2
      echo "Recreate it with scripts/stop.sh and scripts/start-db.sh; the named data volume is preserved." >&2
      return 1
    fi
    twxav2_wait_for_podman_db "$db"
    export UPLOAD_DIR="$TWXAV2_DATA_DIR/uploads"
    return 0
  fi

  if [ "$mode" = "skip" ]; then
    echo "ERROR: --skip-db requested, but the Podman MariaDB container is not running." >&2
    echo "Start it separately with scripts/start-db.sh, then retry." >&2
    return 1
  fi

  podman network exists "$network" || podman network create "$network" >/dev/null
  if podman container exists "$db"; then
    # The bind-mounted data directory survives database-container recreation.
    podman rm -f "$db" >/dev/null
  fi
  podman run -d --name "$db" --network "$network" \
    --label io.twxav2.storage=repository-data \
    -p 127.0.0.1:8081:3306 \
    -e MYSQL_ROOT_PASSWORD="$DB_ROOT_PASSWORD" \
    -e MYSQL_DATABASE=twxav2 \
    -e MYSQL_USER=twxav2 \
    -e MYSQL_PASSWORD="$DB_PASSWORD" \
    -e MARIADB_AUTO_UPGRADE=1 \
    -v "$TWXAV2_DATA_DIR_HOST/mariadb:/var/lib/mysql:z" \
    "$image" >/dev/null

  echo "Waiting for MariaDB (repository data directory: $TWXAV2_DATA_DIR/mariadb)..."
  twxav2_wait_for_podman_db "$db"

  export UPLOAD_DIR="$TWXAV2_DATA_DIR/uploads"
}

twxav2_wait_for_podman_db() {
  local db="$1"
  for _ in $(seq 1 60); do
    if podman exec "$db" sh -c \
        'MYSQL_PWD="$MYSQL_PASSWORD" mariadb --protocol=tcp -h127.0.0.1 -utwxav2 -Dtwxav2 --batch --skip-column-names -e "SELECT 1"' \
        >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "ERROR: MariaDB did not become ready. Check: podman logs $db" >&2
  return 1
}
