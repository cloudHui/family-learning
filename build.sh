#!/bin/sh
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
export MAVEN_OPTS="${MAVEN_OPTS:--Xms64m -Xmx384m}"
cd "$ROOT"
if [ -x ./mvnw ]; then exec ./mvnw test package; fi
command -v mvn >/dev/null 2>&1 || { echo "缺少 Maven" >&2; exit 1; }
exec mvn test package
