#!/usr/bin/env bash
# Run JUnit tests locally (JDK 25 and Maven 3.10.0 are required).
# usage:
#   scripts/test.sh                       -> run all tests
#   scripts/test.sh -Dtest=TwxParserTest  -> run one test for faster feedback
#                                            after a failed build
# Dependencies are downloaded from Maven Central.
set -euo pipefail

if [ -n "${MSYSTEM:-}" ]; then
  REPO="$(cd "$(dirname "$0")/.." && pwd -W)"
else
  REPO="$(cd "$(dirname "$0")/.." && pwd)"
fi
source "$REPO/scripts/local-toolchain.sh"
twxav2_use_local_toolchain "$REPO"
twxav2_require_java_maven
echo "== Tests (${*:-all}) =="
MVN="${MVN_BIN:-mvn}"
if ! command -v "$MVN" >/dev/null 2>&1 && [ ! -f "$MVN" ]; then
  echo "ERROR: Maven not found: $MVN (set MVN_BIN). Maven 3.10.0 and JDK 25 are required." >&2
  exit 1
fi
JACOCO_ARGS=()
for arg in "$@"; do
  case "$arg" in
    -Dtest=*|-Dtest:*) JACOCO_ARGS+=(-Djacoco.skip=true); break ;;
  esac
done
"$MVN" -B -ntp "${JACOCO_ARGS[@]}" -Djacoco.destFile=target/jacoco.exec -Djacoco.dataFile=target/jacoco.exec test "$@"
