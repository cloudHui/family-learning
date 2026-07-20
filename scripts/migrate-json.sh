#!/bin/sh
set -eu
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
DATA_DIR=${DATA_DIR:-$ROOT/data}
exec mvn --batch-mode -q -f "$ROOT/pom.xml" exec:java \
  -Dexec.mainClass=cc.ccwu.familylearning.migration.JsonToSqliteMigration \
  -Dexec.args="--data-dir=$DATA_DIR --database=$DATA_DIR/family-learning.sqlite"
