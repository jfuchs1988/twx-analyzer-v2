#!/usr/bin/env bash
# Prefer the checksum-verified portable tools in ignored temp/ when present.
# Otherwise local developer installations from PATH remain supported.
twxav2_use_local_toolchain() {
  local repo="$1"
  local root="$repo"
  if [ -n "${MSYSTEM:-}" ]; then
    root="$(cygpath -u "$repo")"
  fi
  local jdk="$root/temp/toolchain/jdk/jdk-25.0.5+8"
  local maven="$root/temp/toolchain/maven/apache-maven-3.10.0/bin"
  local node="$root/temp/toolchain/node/node-v24.22.1-win-x64"
  local npm_cli="$root/temp/toolchain/npm12/node_modules/npm/bin/npm-cli.js"

  if [ -z "${JAVA_HOME:-}" ] && { [ -x "$jdk/bin/java" ] || [ -f "$jdk/bin/java.exe" ]; }; then
    export JAVA_HOME="$jdk"
  fi
  if [ -n "${JAVA_HOME:-}" ]; then
    export PATH="$JAVA_HOME/bin:$PATH"
  fi

  if [ -z "${MVN_BIN:-}" ]; then
    if [ -f "$maven/mvn" ]; then
      export MVN_BIN="$maven/mvn"
    elif [ -f "$maven/mvn.cmd" ]; then
      export MVN_BIN="$maven/mvn.cmd"
    fi
  fi

  if [ -f "$node/node.exe" ]; then
    export PATH="$node:$PATH"
    if [ -f "$npm_cli" ]; then
      TWXAV2_NODE_EXE="$node/node.exe"
      TWXAV2_NPM_CLI="$npm_cli"
      npm() { "$TWXAV2_NODE_EXE" "$TWXAV2_NPM_CLI" "$@"; }
    fi
  elif [ -f "$node/node" ]; then
    export PATH="$node:$PATH"
  fi
}

twxav2_require_node_npm() {
  local node_version npm_version
  if ! command -v node >/dev/null 2>&1 || ! command -v npm >/dev/null 2>&1; then
    echo "ERROR: Node.js 24.22.1 and npm 12.2.1 are required." >&2
    return 1
  fi
  node_version="$(node --version 2>&1 | tr -d '\r')"
  npm_version="$(npm --version 2>&1 | tr -d '\r')"
  if [ "$node_version" != "v24.22.1" ] || [ "$npm_version" != "12.2.1" ]; then
    echo "ERROR: expected Node.js v24.22.1/npm 12.2.1; found $node_version/$npm_version." >&2
    return 1
  fi
}

twxav2_require_java_maven() {
  local java_major maven_version mvn="${MVN_BIN:-mvn}"
  if ! command -v java >/dev/null 2>&1; then
    echo "ERROR: JDK 25 is required." >&2
    return 1
  fi
  java_major="$(java -version 2>&1 | sed -n '1s/.*version "\([0-9]*\).*/\1/p' | tr -d '\r')"
  if [ "$java_major" != "25" ]; then
    echo "ERROR: expected JDK 25; found Java $java_major." >&2
    return 1
  fi
  if ! command -v "$mvn" >/dev/null 2>&1 && [ ! -f "$mvn" ]; then
    echo "ERROR: Maven 3.10.x not found: $mvn (set MVN_BIN)." >&2
    return 1
  fi
  maven_version="$("$mvn" --version 2>&1 | sed -n '1s/^Apache Maven \([^ ]*\).*/\1/p' | tr -d '\r')"
  case "$maven_version" in
    3.10.*) ;;
    *) echo "ERROR: expected Maven 3.10.x; found $maven_version." >&2; return 1 ;;
  esac
}
