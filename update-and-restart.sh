#!/bin/sh
set -eu

ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
REMOTE=${REMOTE:-origin}
BRANCH=${BRANCH:-main}
SERVICE=${SERVICE:-family-learning.service}
ENV_FILE=${ENV_FILE:-/etc/family-learning/family-learning.env}
APP_JAR=${APP_JAR:-/opt/family-learning/family-learning.jar}
APP_WORKDIR=${APP_WORKDIR:-$(dirname "$APP_JAR")}
LOG_DIR=${LOG_DIR:-}
ACCESS_CODE=${ACCESS_CODE:-}
APP_URL=${APP_URL:-}
HEALTH_TIMEOUT=${HEALTH_TIMEOUT:-180}
BACKUP="${APP_JAR}.previous"

say() { printf '%s\n' "[family-learning-update] $*"; }
die() { say "错误：$*" >&2; exit 1; }
load_config() {
  configured_access_code=
  configured_log_dir=
  if [ -f "$ENV_FILE" ]; then
    configured_access_code=$(sed -n 's/^ACCESS_CODE=//p' "$ENV_FILE" | tail -1)
    configured_log_dir=$(sed -n 's/^LOG_DIR=//p' "$ENV_FILE" | tail -1)
  else
    say "提示：配置文件不存在，使用默认健康检查和日志路径: $ENV_FILE"
  fi
  [ -n "$ACCESS_CODE" ] || ACCESS_CODE=${configured_access_code#/}
  [ -n "$ACCESS_CODE" ] || ACCESS_CODE=family-learning
  [ -n "$LOG_DIR" ] || LOG_DIR=${configured_log_dir:-$APP_WORKDIR/logs}
  [ -n "$APP_URL" ] || APP_URL="http://127.0.0.1:8088/$ACCESS_CODE/"
}

ensure_log_config() {
  install -d -m 750 /var/log/family-learning
  if id family-learning >/dev/null 2>&1; then
    chown family-learning:family-learning /var/log/family-learning
  fi
  [ -f "$ENV_FILE" ] || return 0
  if grep -q '^LOG_DIR=' "$ENV_FILE"; then
    sed -i 's#^LOG_DIR=.*#LOG_DIR=/var/log/family-learning#' "$ENV_FILE"
  else
    printf '%s\n' 'LOG_DIR=/var/log/family-learning' >>"$ENV_FILE"
  fi
}

ensure_service_logging_access() {
  dropin_dir=/etc/systemd/system/$SERVICE.d
  dropin_file=$dropin_dir/logging.conf
  install -d -m 755 "$dropin_dir"
  printf '%s\n' \
    '[Service]' \
    'ReadWritePaths=/var/lib/family-learning /var/lib/family-learning/resources /var/lib/family-learning/datasets /var/log/family-learning' \
    >"$dropin_file"
  systemctl daemon-reload
}

prepare_log_dirs() {
  for log_dir in "$LOG_DIR" "$APP_WORKDIR/logs"; do
    install -d -m 750 "$log_dir"
    if id family-learning >/dev/null 2>&1; then
      chown family-learning:family-learning "$log_dir"
    fi
  done
}

show_status() {
  say "===== systemd 状态 ====="
  systemctl status "$SERVICE" --no-pager --full 2>&1 || true
}

print_log_dir() {
  log_dir=$1
  [ -d "$log_dir" ] || return 0
  say "===== 应用日志目录: $log_dir ====="
  find "$log_dir" -maxdepth 1 -type f -print -exec sh -c \
    'echo "----- $1 -----"; cat "$1"' sh {} \; 2>&1 || true
}

print_logs() {
  say "===== systemd 日志 ====="
  journalctl -u "$SERVICE" -n 1000 --no-pager 2>&1 || true
  print_log_dir "$LOG_DIR"
  fallback_log_dir="$APP_WORKDIR/logs"
  [ "$fallback_log_dir" = "$LOG_DIR" ] || print_log_dir "$fallback_log_dir"
  [ -d "$LOG_DIR" ] || [ -d "$fallback_log_dir" ] || say "应用日志目录不存在"
}

health_check() {
  # /api/health 需登录；部署探针改查服务状态与首页
  deadline=$(( $(date +%s) + HEALTH_TIMEOUT ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    if systemctl is-active --quiet "$SERVICE" 2>/dev/null && curl -fsS --max-time 2 "$APP_URL" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  return 1
}

restart_and_check() {
  say "重启服务: $SERVICE"
  if ! systemctl restart "$SERVICE"; then
    say "systemd 重启命令失败"
    return 1
  fi
  say "健康检查: $APP_URL"
  health_check
}

rollback() {
  [ -f "$BACKUP" ] || { say "没有上一版本 JAR，无法回滚"; return 1; }
  say "恢复上一版本 JAR: $BACKUP"
  install -m 644 "$BACKUP" "$APP_JAR" || return 1
  systemctl restart "$SERVICE" || return 1
  say "检查回滚版本健康状态"
  health_check
}

[ "$(id -u)" -eq 0 ] || exec sudo "$0" "$@"
cd "$ROOT"
ensure_log_config
load_config
prepare_log_dirs
ensure_service_logging_access

say "拉取远端代码: $REMOTE/$BRANCH"
git pull --ff-only "$REMOTE" "$BRANCH"
say "执行测试并打包"
MAVEN_OPTS=${MAVEN_OPTS:--Xms64m -Xmx384m} mvn --batch-mode test package
[ -f target/family-learning.jar ] || die "没有生成 target/family-learning.jar"

if [ -f "$APP_JAR" ]; then cp -f "$APP_JAR" "$BACKUP"; fi
install -d -m 755 "$(dirname "$APP_JAR")"
install -m 644 target/family-learning.jar "$APP_JAR"
if restart_and_check; then
  say "更新完成，健康检查通过"
  exit 0
fi

say "新版本启动失败，开始回滚"
if rollback; then
  say "已恢复上一版本 JAR，健康检查通过"
else
  say "回滚失败或回滚版本健康检查未通过"
fi
show_status
print_logs
exit 1
