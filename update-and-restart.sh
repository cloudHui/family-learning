#!/bin/sh
set -eu

# 兼容旧入口：统一使用正式更新流程，确保 JAR、数据集、资源、systemd unit
# 和 CLI 一起更新，并使用同一套健康检查与回滚逻辑。
ROOT=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
[ "$(id -u)" -eq 0 ] || exec sudo "$0" "$@"
exec "$ROOT/install.sh" update
