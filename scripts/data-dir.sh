#!/usr/bin/env bash
# Resolve persistent host data under the repository's ignored data/ directory.

twxav2_init_data_dir() {
  local repo="$1"
  local repo_path common_dir data_root data_path

  repo_path="$(cd "$repo" && pwd -P)"
  common_dir="$(git -C "$repo" rev-parse --git-common-dir 2>/dev/null || true)"
  if [ -n "$common_dir" ]; then
    if [ -n "${MSYSTEM:-}" ]; then
      common_dir="$(cygpath -u "$common_dir")"
    fi
    case "$common_dir" in
      /*) ;;
      *) common_dir="$repo_path/$common_dir" ;;
    esac
    common_dir="$(cd "$common_dir" && pwd -P)"
    data_root="$(dirname "$common_dir")/data"
  else
    data_root="$repo_path/data"
  fi

  local requested="${TWXAV2_DATA_DIR:-$data_root}"
  if [ -n "${MSYSTEM:-}" ]; then
    requested="$(cygpath -u "$requested")"
  fi
  data_path="$(realpath -m "$requested")"
  if [ "$data_path" != "$data_root" ]; then
    echo "ERROR: TWXAV2_DATA_DIR must resolve to the shared repository data/ directory: $data_root" >&2
    return 1
  fi
  mkdir -p "$data_path/uploads" "$data_path/mariadb"
  data_path="$(cd "$data_path" && pwd -P)"

  TWXAV2_DATA_DIR="$data_path"
  TWXAV2_DATA_DIR_HOST="$data_path"
  if [ -n "${MSYSTEM:-}" ]; then
    TWXAV2_DATA_DIR_HOST="$(cygpath -w "$data_path")"
  fi
  export TWXAV2_DATA_DIR TWXAV2_DATA_DIR_HOST
}

twxav2_load_db_env() {
  local mode="${1:-start}"
  local existing_db="${2:-false}"
  local file="$TWXAV2_DATA_DIR/db.env"
  if [ ! -f "$file" ]; then
    if [ "$mode" = "skip" ] || [ "$existing_db" = "true" ]; then
      echo "ERROR: $file is missing; refusing to use existing persistent database data with new credentials." >&2
      echo "Restore the original db.env to TWXAV2_DATA_DIR before continuing." >&2
      return 1
    fi
    (umask 077; {
      echo "DB_PASSWORD=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
      echo "DB_ROOT_PASSWORD=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"
      echo "DB_SECRET=$(tr -dc 'A-Za-z0-9' </dev/urandom | head -c 64)"
    } > "$file")
  fi
  chmod 600 "$file" 2>/dev/null || true
  # shellcheck disable=SC1090
  set -a
  source "$file"
  set +a
  if [ -z "${DB_PASSWORD:-}" ] || [ -z "${DB_ROOT_PASSWORD:-}" ]; then
    echo "ERROR: DB_PASSWORD and DB_ROOT_PASSWORD must be set in $file." >&2
    return 1
  fi
}
