#!/bin/sh
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REMOTE=${REMOTE:-origin}
BRANCH=${BRANCH:-main}
SERVICE=${SERVICE:-family-learning.service}
APP_JAR=${APP_JAR:-$(dirname "$ROOT")/family-learning.jar}
LOG_DIR=${LOG_DIR:-/var/log/family-learning}
APP_URL=${APP_URL:-http://127.0.0.1:8088/family-learning/api/health}
BACKUP="${APP_JAR}.previous"

say() { printf '%s\n' "[family-learning-update] $*"; }
die() { say "错误：$*" >&2; exit 1; }
print_logs() {
  say "===== systemd 日志 ====="
  journalctl -u "$SERVICE" -n 1000 --no-pager 2>&1 || true
  say "===== 应用日志目录: $LOG_DIR ====="
  if [ -d "$LOG_DIR" ]; then
    find "$LOG_DIR" -maxdepth 1 -type f -print -exec sh -c 'echo "----- $1 -----"; cat "$1"' sh {} \; 2>&1 || true
  else
    say "日志目录不存在"
  fi
}

[ "$(id -u)" -eq 0 ] || exec sudo "$0" "$@"
cd "$ROOT"

say "拉取远端代码: $REMOTE/$BRANCH"
git pull --ff-only "$REMOTE" "$BRANCH"
say "执行测试并打包"
mvn --batch-mode test package
[ -f target/family-learning.jar ] || die "没有生成 target/family-learning.jar"

if [ -f "$APP_JAR" ]; then cp -f "$APP_JAR" "$BACKUP"; fi
install -m 644 target/family-learning.jar "$APP_JAR"
say "重启服务: $SERVICE"
systemctl restart "$SERVICE"

count=0
while [ "$count" -lt 30 ]; do
  if curl -fsS --max-time 2 "$APP_URL" >/dev/null 2>&1; then
    say "更新完成，健康检查通过"
    exit 0
  fi
  count=$((count + 1))
  sleep 1
done

say "重启失败，开始回滚"
if [ -f "$BACKUP" ]; then
  install -m 644 "$BACKUP" "$APP_JAR"
  systemctl restart "$SERVICE" || true
  say "已恢复上一版本 JAR"
else
  say "没有上一版本 JAR，无法回滚"
fi
print_logs
exit 1
