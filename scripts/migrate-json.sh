#!/bin/sh
set -eu
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")/.." && pwd)
DATA_DIR=${DATA_DIR:-$ROOT/data}
DB=$DATA_DIR/family-learning.sqlite
JAR=$ROOT/target/family-learning.jar
MAIN=cc.ccwu.familylearning.migration.JsonToSqliteMigration
ARGS="--data-dir=$DATA_DIR --database=$DB"

# 优先用已构建的 fat jar，避免再起一套 Maven（1G 机器容易 OOM）
if [ -f "$JAR" ]; then
  exec java -Xms16m -Xmx96m -XX:+UseSerialGC \
    -Dloader.main="$MAIN" \
    -cp "$JAR" \
    org.springframework.boot.loader.PropertiesLauncher \
    $ARGS
fi

exec mvn --batch-mode -q -f "$ROOT/pom.xml" exec:java \
  -Dexec.mainClass="$MAIN" \
  -Dexec.args="$ARGS"
