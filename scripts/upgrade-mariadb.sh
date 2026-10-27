#!/usr/bin/env bash
# Safely migrate the repository-backed MariaDB datadir to the configured image.
set -Eeuo pipefail

if [ -n "${MSYSTEM:-}" ]; then
  export MSYS2_ARG_CONV_EXCL='*'
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
source "$REPO/scripts/podman-db.sh"
twxav2_require_podman
source "$REPO/scripts/data-dir.sh"
twxav2_init_data_dir "$REPO"

DB=twxav2-db
TARGET_IMAGE="${MARIADB_IMAGE:-docker.io/library/mariadb:12.3.3}"
NETWORK=twxav2
if ! podman ps -f "name=$DB" --format '{{.Names}}' | grep -qx "$DB"; then
  echo "ERROR: Start the existing MariaDB container before running this upgrade." >&2
  exit 1
fi
twxav2_load_db_env skip true
export DB_HOST=127.0.0.1 DB_PORT=8081 DB_NAME=twxav2 DB_USER=twxav2
CURRENT_VERSION="$(podman exec --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$DB" \
  mariadb --protocol=socket -uroot --batch --skip-column-names -e 'SELECT VERSION()' | tr -d '\r')"
case "$CURRENT_VERSION" in
  12.3.3*)
    echo "MariaDB is already $CURRENT_VERSION; no migration is needed."
    exit 0
    ;;
  11.8.*) ;;
  *)
    echo "ERROR: This migration is tested for MariaDB 11.8.x to 12.3.3; found $CURRENT_VERSION." >&2
    exit 1
    ;;
esac

SOURCE_KB="$(du -sk "$TWXAV2_DATA_DIR/mariadb" | awk '{print $1}')"
FREE_KB="$(df -Pk "$TWXAV2_DATA_DIR" | awk 'END {print $4}')"
REQUIRED_KB=$((SOURCE_KB * 3 + 524288))
if [ "$FREE_KB" -lt "$REQUIRED_KB" ]; then
  echo "ERROR: At least $((REQUIRED_KB / 1024)) MiB free disk space is required for a safe backup/restore upgrade." >&2
  exit 1
fi

podman image exists "$TARGET_IMAGE" || podman pull "$TARGET_IMAGE"
ID="$(date +%Y%m%d-%H%M%S)-$$"
DUMP="$TWXAV2_DATA_DIR/.mariadb-upgrade-$ID.sql"
TEST_DIR="$TWXAV2_DATA_DIR/.mariadb-upgrade-test-$ID"
BACKUP_DIR="$TWXAV2_DATA_DIR/mariadb-11.8.9-rollback-$ID"
BACKUP_VOLUME="twxav2-db-11.8.9-rollback-$ID"
TEST_DB="twxav2-db-12.3.3-test-$ID"
DB_STOPPED=0
BACKUP_READY=0
OLD_DIR_SAVED=0
LIVE_UPGRADE_STARTED=0
LIVE_VERIFIED=0
READ_ONLY_GATED=0
ORIGINAL_READ_ONLY=0

core_signature() {
  podman exec --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$1" mariadb --protocol=socket -uroot -Dtwxav2 \
    --batch --skip-column-names -e \
    "SELECT CONCAT((SELECT COUNT(*) FROM snapshot),':',(SELECT COUNT(*) FROM twx_object),':',(SELECT COUNT(*) FROM finding),':',(SELECT COUNT(*) FROM toolkit_snapshot))" \
    | tr -d '\r'
}

read_only_state() {
  podman exec --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$1" mariadb --protocol=socket -uroot \
    --batch --skip-column-names -e 'SELECT @@GLOBAL.read_only' | tr -d '\r'
}

set_read_only() {
  podman exec --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$1" mariadb --protocol=socket -uroot \
    -e "SET GLOBAL read_only=$2" >/dev/null
}

wait_for_test_db() {
  local container="$1" version
  for _ in $(seq 1 180); do
    version="$(podman exec --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$container" mariadb --protocol=socket -uroot \
      --batch --skip-column-names -e 'SELECT VERSION()' 2>/dev/null | tr -d '\r' || true)"
    case "$version" in
      12.3.3*) return 0 ;;
    esac
    sleep 2
  done
  podman logs "$container" | tail -80
  return 1
}

start_live_db() {
  local image="$1" read_only="${2:-0}"
  local server_args=()
  [ "$read_only" = 1 ] && server_args+=(--read-only=ON)
  podman rm -f "$DB" >/dev/null 2>&1 || true
  podman network exists "$NETWORK" || podman network create "$NETWORK" >/dev/null
  podman run -d --name "$DB" --network "$NETWORK" \
    --label io.twxav2.storage=repository-data -p 127.0.0.1:8081:3306 \
    -e "MYSQL_ROOT_PASSWORD=$DB_ROOT_PASSWORD" -e MYSQL_DATABASE=twxav2 \
    -e MYSQL_USER=twxav2 -e "MYSQL_PASSWORD=$DB_PASSWORD" -e MARIADB_AUTO_UPGRADE=1 \
    -v "$TWXAV2_DATA_DIR_HOST/mariadb:/var/lib/mysql:z" "$image" "${server_args[@]}" >/dev/null
  twxav2_wait_for_podman_db "$DB"
}

restore_old_datadir() {
  set +e
  podman rm -f "$DB" >/dev/null 2>&1
  if [ "$OLD_DIR_SAVED" = 1 ] && [ -d "$BACKUP_DIR" ]; then
    if [ -d "$TWXAV2_DATA_DIR/mariadb" ]; then
      mv "$TWXAV2_DATA_DIR/mariadb" "$TWXAV2_DATA_DIR/.mariadb-upgrade-failed-$ID"
    fi
    mv "$BACKUP_DIR" "$TWXAV2_DATA_DIR/mariadb"
  elif [ "$BACKUP_READY" = 1 ] && [ -d "$TWXAV2_DATA_DIR/mariadb" ]; then
    find "$TWXAV2_DATA_DIR/mariadb" -mindepth 1 -maxdepth 1 -exec rm -rf -- {} +
    podman run --rm --network none -v "$BACKUP_VOLUME:/source:ro" \
      -v "$TWXAV2_DATA_DIR_HOST/mariadb:/target:z" --entrypoint sh docker.io/library/alpine:3.22 \
      -c 'cp -a /source/. /target/' >/dev/null 2>&1
  fi
  MARIADB_IMAGE=docker.io/library/mariadb:11.8.9 start_live_db docker.io/library/mariadb:11.8.9 >/dev/null
}

cleanup() {
  local rc=$?
  trap - EXIT ERR
  set +e
  podman rm -f "$TEST_DB" >/dev/null 2>&1
  if [ "$LIVE_VERIFIED" != 1 ]; then
    if [ "$DB_STOPPED" = 1 ]; then
      if [ "$LIVE_UPGRADE_STARTED" = 1 ] || [ "$OLD_DIR_SAVED" = 1 ]; then
        restore_old_datadir
      else
        start_live_db docker.io/library/mariadb:11.8.9 1 >/dev/null
      fi
      set_read_only "$DB" "$ORIGINAL_READ_ONLY"
    elif [ "$READ_ONLY_GATED" = 1 ]; then
      set_read_only "$DB" "$ORIGINAL_READ_ONLY"
    fi
  fi
  rm -f "$DUMP"
  if [ -d "$TEST_DIR" ]; then
    find "$TEST_DIR" -mindepth 1 -delete
    rmdir "$TEST_DIR" 2>/dev/null
  fi
  if [ "$BACKUP_READY" != 1 ]; then
    podman volume rm "$BACKUP_VOLUME" >/dev/null 2>&1
  fi
  exit "$rc"
}
trap cleanup EXIT
trap 'exit 1' ERR

BASE_CORE="$(core_signature "$DB")"
ORIGINAL_READ_ONLY="$(read_only_state "$DB")"
case "$ORIGINAL_READ_ONLY" in
  0|1) ;;
  *) echo "ERROR: Could not determine the current read-only mode." >&2; exit 1 ;;
esac
if [ "$ORIGINAL_READ_ONLY" = 0 ]; then
  set_read_only "$DB" 1
  READ_ONLY_GATED=1
fi
umask 077
podman volume create "$BACKUP_VOLUME" >/dev/null
if ! podman exec --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$DB" \
    mariadb-dump --protocol=socket -uroot --single-transaction --quick --routines --events --triggers \
    --databases twxav2 > "$DUMP"; then
  echo "ERROR: Could not create the logical backup; the live database was not changed." >&2
  exit 1
fi
if [ ! -s "$DUMP" ]; then
  echo "ERROR: Logical backup is empty; the live database was not changed." >&2
  exit 1
fi

mkdir -p "$TEST_DIR"
TEST_DIR_HOST="$TEST_DIR"
if [ -n "${MSYSTEM:-}" ]; then
  TEST_DIR_HOST="$(cygpath -w "$TEST_DIR")"
fi
podman run -d --name "$TEST_DB" --network none \
  -e "MYSQL_ROOT_PASSWORD=$DB_ROOT_PASSWORD" -e MYSQL_DATABASE=twxav2 \
  -e MYSQL_USER=twxav2 -e "MYSQL_PASSWORD=$DB_PASSWORD" \
  -v "$TEST_DIR_HOST:/var/lib/mysql:z" "$TARGET_IMAGE" >/dev/null
wait_for_test_db "$TEST_DB"
podman exec -i --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$TEST_DB" mariadb --protocol=socket -uroot < "$DUMP"
TEST_CORE="$(core_signature "$TEST_DB")"
if [ "$TEST_CORE" != "$BASE_CORE" ]; then
  echo "ERROR: Restored MariaDB 12.3.3 test data does not match the source core-table counts." >&2
  exit 1
fi
podman exec --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$TEST_DB" \
  mariadb-check --protocol=socket -uroot --all-databases --check >/dev/null
podman stop --time 120 "$TEST_DB" >/dev/null
podman rm "$TEST_DB" >/dev/null

podman stop --time 120 "$DB" >/dev/null
DB_STOPPED=1
podman run --rm --network none --volumes-from "$DB":ro -v "$BACKUP_VOLUME:/backup" \
  --entrypoint sh docker.io/library/alpine:3.22 -c 'cp -a /var/lib/mysql/. /backup/' >/dev/null
BACKUP_READY=1
mv "$TWXAV2_DATA_DIR/mariadb" "$BACKUP_DIR"
OLD_DIR_SAVED=1
mv "$TEST_DIR" "$TWXAV2_DATA_DIR/mariadb"
LIVE_UPGRADE_STARTED=1
start_live_db "$TARGET_IMAGE" 1
LIVE_VERSION="$(podman exec --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$DB" \
  mariadb --protocol=socket -uroot --batch --skip-column-names -e 'SELECT VERSION()' | tr -d '\r')"
LIVE_CORE="$(core_signature "$DB")"
if [[ "$LIVE_VERSION" != 12.3.3* || "$LIVE_CORE" != "$BASE_CORE" ]]; then
  echo "ERROR: Live MariaDB verification failed; initiating rollback." >&2
  exit 1
fi
podman exec --env "MYSQL_PWD=$DB_ROOT_PASSWORD" "$DB" \
  mariadb-check --protocol=socket -uroot --all-databases --check >/dev/null
set_read_only "$DB" "$ORIGINAL_READ_ONLY"
READ_ONLY_GATED=0
LIVE_VERIFIED=1
DB_STOPPED=0

API_STATUS="$(curl.exe --silent --output /dev/null --write-out '%{http_code}' --max-time 15 \
  http://127.0.0.1:8080/api/snapshots 2>/dev/null || true)"
echo "PASS: test-volume logical backup/restore validated on MariaDB 12.3.3."
echo "PASS: active database migrated $CURRENT_VERSION -> $LIVE_VERSION; core table counts preserved ($LIVE_CORE)."
echo "PASS: all database tables pass mariadb-check; rollback copy retained."
echo "Existing local API status after reconnect attempt: ${API_STATUS:-unavailable}"
