#!/bin/sh
set -eu

REPO_URL=${REPO_URL:-https://github.com/cloudHui/family-learning.git}
APP_DIR=/opt/family-learning
SOURCE_DIR=$APP_DIR/source
DATA_DIR=/var/lib/family-learning
RESOURCE_DIR=$DATA_DIR/resources
DATASET_DIR=$DATA_DIR/datasets
LOG_DIR=/var/log/family-learning
CONFIG_DIR=/etc/family-learning
ENV_FILE=$CONFIG_DIR/family-learning.env
SERVICE=family-learning.service
NGINX_CONF=/etc/nginx/conf.d/family-learning.conf
ACTION=${1:-install}

say() { printf '%s\n' "[family-learning] $*"; }
die() { printf '%s\n' "[family-learning] 错误：$*" >&2; exit 1; }
has() { command -v "$1" >/dev/null 2>&1; }
ask() {
  prompt=$1 default=$2
  if [ -r /dev/tty ]; then
    printf '%s [%s]: ' "$prompt" "$default" >/dev/tty
    IFS= read -r ANSWER </dev/tty || ANSWER=
  else ANSWER=; fi
  ANSWER=${ANSWER:-$default}
}

[ "$(id -u)" -eq 0 ] || die "请使用 sudo 运行"

preflight() {
  arch=$(uname -m); free_kb=$(df -Pk /opt 2>/dev/null | awk 'NR==2{print $4}')
  case "$arch" in x86_64|amd64|aarch64|arm64) ;; *) say "提示：未专项验证的架构 $arch" ;; esac
  [ "${free_kb:-0}" -ge 524288 ] || die "/opt 可用磁盘不足 512MB"
  say "系统 $(uname -s) / $arch，磁盘检查通过"
}

install_packages() {
  need_build=false; need_nginx=false
  has git && has curl && has mvn && has javac || need_build=true
  has nginx || need_nginx=true
  $need_build || $need_nginx || return 0
  say "安装缺少的 Git、JDK、Maven、curl 或 Nginx"
  if has apt-get; then
    apt-get update
    DEBIAN_FRONTEND=noninteractive apt-get install -y git curl default-jdk-headless maven nginx
  elif has dnf; then
    dnf install -y git curl java-17-openjdk-devel maven nginx
  elif has yum; then
    yum install -y git curl java-17-openjdk-devel maven nginx
  else die "仅自动支持 apt、dnf 和 yum，请先安装 Git、JDK、Maven、curl、Nginx"; fi
}

load_or_prompt_config() {
  if [ -f "$ENV_FILE" ]; then
    ACCESS_CODE=$(sed -n 's/^ACCESS_CODE=//p' "$ENV_FILE" | tail -1)
    PUBLIC_IP=$(sed -n 's/^PUBLIC_IP=//p' "$ENV_FILE" | tail -1)
    DOMAIN=$(sed -n 's/^DOMAIN=//p' "$ENV_FILE" | tail -1)
    ENABLE_HTTPS=$(sed -n 's/^ENABLE_HTTPS=//p' "$ENV_FILE" | tail -1)
    MAIL_HOST=$(sed -n 's/^MAIL_HOST=//p' "$ENV_FILE" | tail -1)
    MAIL_PORT=$(sed -n 's/^MAIL_PORT=//p' "$ENV_FILE" | tail -1)
    MAIL_USERNAME=$(sed -n 's/^MAIL_USERNAME=//p' "$ENV_FILE" | tail -1)
    MAIL_PASSWORD=$(sed -n 's/^MAIL_PASSWORD=//p' "$ENV_FILE" | tail -1)
    REPORT_RECIPIENT=$(sed -n 's/^REPORT_RECIPIENT=//p' "$ENV_FILE" | tail -1)
    CERT_EMAIL=$(sed -n 's/^CERT_EMAIL=//p' "$ENV_FILE" | tail -1)
    ACCESS_CODE=${ACCESS_CODE#/}
    return
  fi
  ACCESS_CODE=${ACCESS_CODE:-$(od -An -N12 -tx1 /dev/urandom | tr -d ' \n')}
  PUBLIC_IP=${PUBLIC_IP:-}
  DOMAIN=${DOMAIN:-}
  ENABLE_HTTPS=${ENABLE_HTTPS:-no}
  if [ -r /dev/tty ]; then
    ask "是否配置公网 IP（yes/no）" "no"; configure_ip=$ANSWER
    case "$configure_ip" in y|Y|yes|YES) ask "请输入公网 IP" ""; PUBLIC_IP=$ANSWER ;; esac
    ask "域名（没有直接回车）" "$DOMAIN"; DOMAIN=$ANSWER
    ask "访问唯一码" "$ACCESS_CODE"; ACCESS_CODE=$ANSWER
    if [ -n "$DOMAIN" ]; then ask "是否申请并启用 HTTPS（yes/no）" "$ENABLE_HTTPS"; ENABLE_HTTPS=$ANSWER; fi
  fi
  printf '%s' "$ACCESS_CODE" | grep -Eq '^[A-Za-z0-9_-]{8,64}$' || die "访问唯一码应为8到64位字母、数字、_或-"
  [ -z "$PUBLIC_IP" ] || printf '%s' "$PUBLIC_IP" | grep -Eq '^[0-9A-Fa-f:.]+$' || die "公网 IP 格式不正确"
  [ -z "$DOMAIN" ] || printf '%s' "$DOMAIN" | grep -Eq '^[A-Za-z0-9.-]+$' || die "域名格式不正确"
}

write_env() {
  install -d -m 750 "$CONFIG_DIR" "$DATA_DIR" "$RESOURCE_DIR" "$DATASET_DIR" /var/log/family-learning
  cat >"$ENV_FILE" <<EOF
APP_ADDRESS=127.0.0.1
APP_PORT=8088
ACCESS_CODE=$ACCESS_CODE
DATA_DIR=$DATA_DIR
RESOURCE_DIR=$RESOURCE_DIR
DATASET_DIR=$DATASET_DIR
LOG_DIR=$LOG_DIR
TOMCAT_MAX_THREADS=40
REPORT_ZONE=Asia/Shanghai
REPORT_CRON='0 55 23 * * *'
PUBLIC_IP=$PUBLIC_IP
DOMAIN=$DOMAIN
ENABLE_HTTPS=$ENABLE_HTTPS
MAIL_HOST=${MAIL_HOST:-}
MAIL_PORT=${MAIL_PORT:-465}
MAIL_USERNAME=${MAIL_USERNAME:-}
MAIL_PASSWORD=${MAIL_PASSWORD:-}
REPORT_RECIPIENT=${REPORT_RECIPIENT:-}
CERT_EMAIL=${CERT_EMAIL:-}
EOF
  chmod 600 "$ENV_FILE"
}

fetch_source() {
  install -d -m 755 "$APP_DIR"
  if [ -d "$SOURCE_DIR/.git" ]; then
    git -C "$SOURCE_DIR" fetch --depth 1 origin main
    git -C "$SOURCE_DIR" reset --hard origin/main
  else
    [ ! -e "$SOURCE_DIR" ] || mv "$SOURCE_DIR" "$SOURCE_DIR.unmanaged.$(date +%s)"
    git clone --depth 1 "$REPO_URL" "$SOURCE_DIR"
  fi
}

build_jar() {
  say "运行测试并构建 JAR"
  (cd "$SOURCE_DIR" && MAVEN_OPTS='-Xms64m -Xmx384m' mvn --batch-mode test package)
}

migrate_json() {
  say "迁移旧 JSON 数据到 SQLite（失败不修改原 JSON）"
  (cd "$SOURCE_DIR" && DATA_DIR="$DATA_DIR" ./scripts/migrate-json.sh)
}

install_datasets() {
  install -d -m 750 "$DATASET_DIR" "$RESOURCE_DIR/english/kids"
  if [ -f "$SOURCE_DIR/datasets/characters.tar.gz" ]; then
    rm -rf "$DATASET_DIR/characters"
    tar -xzf "$SOURCE_DIR/datasets/characters.tar.gz" -C "$DATASET_DIR"
  fi
  if [ -f "$SOURCE_DIR/datasets/dictionary.tar.gz" ]; then
    rm -rf "$DATASET_DIR/dictionary"
    tar -xzf "$SOURCE_DIR/datasets/dictionary.tar.gz" -C "$DATASET_DIR"
  fi
  if [ -f "$SOURCE_DIR/datasets/poetry.jsonl.gz" ]; then
    gzip -dc "$SOURCE_DIR/datasets/poetry.jsonl.gz" >"$DATASET_DIR/poetry.jsonl"
  fi
  # 诗词分片索引：有打包则解压，否则现场生成
  if [ -f "$SOURCE_DIR/datasets/poetry-idx.tar.gz" ]; then
    rm -rf "$DATASET_DIR/poetry-idx" "$DATASET_DIR/poetry.idx"
    tar -xzf "$SOURCE_DIR/datasets/poetry-idx.tar.gz" -C "$DATASET_DIR"
  elif [ -f "$DATASET_DIR/poetry.jsonl" ] && [ -f "$SOURCE_DIR/scripts/import-datasets.py" ]; then
    python3 "$SOURCE_DIR/scripts/import-datasets.py" poetry-index "$DATASET_DIR/poetry.jsonl"
  fi
  if [ -f "$SOURCE_DIR/datasets/textbooks.json" ]; then
    install -m 644 "$SOURCE_DIR/datasets/textbooks.json" "$DATASET_DIR/textbooks.json"
  fi
  if [ -f "$SOURCE_DIR/datasets/english-kids.tar.gz" ]; then
    rm -rf "$RESOURCE_DIR/english/kids"
    install -d -m 750 "$RESOURCE_DIR/english"
    tar -xzf "$SOURCE_DIR/datasets/english-kids.tar.gz" -C "$RESOURCE_DIR/english"
    if [ -d "$RESOURCE_DIR/english/english-kids" ]; then
      mv "$RESOURCE_DIR/english/english-kids" "$RESOURCE_DIR/english/kids"
    fi
  fi
  chown -R family-learning:family-learning "$DATA_DIR"
}

install_service() {
  if ! id family-learning >/dev/null 2>&1; then
    useradd --system --home-dir "$DATA_DIR" --shell /usr/sbin/nologin family-learning
  fi
  install_datasets
  chown -R family-learning:family-learning "$DATA_DIR"
  chown family-learning:family-learning "$LOG_DIR"
  if [ -f "$APP_DIR/family-learning.jar" ]; then
    cp -f "$APP_DIR/family-learning.jar" "$APP_DIR/family-learning.jar.previous"
  fi
  install -m 644 "$SOURCE_DIR/target/family-learning.jar" "$APP_DIR/family-learning.jar"
  install -m 644 "$SOURCE_DIR/deploy/family-learning.service" /etc/systemd/system/family-learning.service
  install -m 755 "$SOURCE_DIR/deploy/family-learning-cli" /usr/local/bin/family-learning
  systemctl daemon-reload
  systemctl enable "$SERVICE"
  systemctl restart "$SERVICE"
}

write_nginx_http() {
  local_ip=$(hostname -I 2>/dev/null | awk '{print $1}')
  server_name=${DOMAIN:-${PUBLIC_IP:-_ $local_ip}}
  install -d -m 755 /var/www/family-learning-acme /etc/nginx/conf.d
  cat >"$NGINX_CONF" <<EOF
server {
    listen 80;
    listen [::]:80;
    server_name $server_name;
    client_max_body_size 50m;

    location ^~ /.well-known/acme-challenge/ { root /var/www/family-learning-acme; }
    location = /$ACCESS_CODE { return 301 /$ACCESS_CODE/; }
    location ^~ /$ACCESS_CODE/ {
        proxy_pass http://127.0.0.1:8088;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$scheme;
    }
    location / { return 404; }
}
EOF
  nginx -t
  systemctl enable nginx
  systemctl restart nginx
}

configure_firewall() {
  if has ufw && ufw status 2>/dev/null | grep -q '^Status: active'; then
    ufw allow 22/tcp >/dev/null; ufw allow 80/tcp >/dev/null; ufw allow 443/tcp >/dev/null
  elif has firewall-cmd && firewall-cmd --state >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port=22/tcp >/dev/null
    firewall-cmd --permanent --add-service=http >/dev/null
    firewall-cmd --permanent --add-service=https >/dev/null
    firewall-cmd --reload >/dev/null
  fi
}

enable_https() {
  HTTPS_ACTIVE=no
  case "$ENABLE_HTTPS" in y|Y|yes|YES) ;; *) return ;; esac
  [ -n "$DOMAIN" ] || die "启用 HTTPS 必须填写域名"
  if ss -lntp 2>/dev/null | grep -E ':443[[:space:]]' | grep -vq nginx; then
    say "443 已由其他程序占用，保留 HTTP；请参照文档配置 SNI 分流"
    return
  fi
  if ! has certbot; then
    if has apt-get; then DEBIAN_FRONTEND=noninteractive apt-get install -y certbot
    elif has dnf; then dnf install -y certbot
    elif has yum; then yum install -y certbot
    else die "请安装 certbot 后再启用 HTTPS"; fi
  fi
  [ -n "${CERT_EMAIL:-}" ] || { ask "证书通知邮箱" ""; CERT_EMAIL=$ANSWER; }
  [ -n "$CERT_EMAIL" ] || die "申请证书需要邮箱"
  certbot certonly --webroot -w /var/www/family-learning-acme -d "$DOMAIN" --email "$CERT_EMAIL" --agree-tos --non-interactive
  install -d -m 755 /etc/letsencrypt/renewal-hooks/deploy
  cat >/etc/letsencrypt/renewal-hooks/deploy/family-learning-nginx-reload <<'EOF'
#!/bin/sh
systemctl reload nginx
EOF
  chmod 755 /etc/letsencrypt/renewal-hooks/deploy/family-learning-nginx-reload
  cat >>"$NGINX_CONF" <<EOF

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name $DOMAIN;
    ssl_certificate /etc/letsencrypt/live/$DOMAIN/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/$DOMAIN/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    add_header Strict-Transport-Security "max-age=31536000" always;
    client_max_body_size 50m;
    location = /$ACCESS_CODE { return 301 /$ACCESS_CODE/; }
    location ^~ /$ACCESS_CODE/ {
        proxy_pass http://127.0.0.1:8088;
        proxy_http_version 1.1;
        proxy_set_header Host \$host;
        proxy_set_header X-Real-IP \$remote_addr;
        proxy_set_header X-Forwarded-For \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto https;
    }
    location / { return 404; }
}
EOF
  nginx -t && systemctl reload nginx
  HTTPS_ACTIVE=yes
}

health_check() {
  url="http://127.0.0.1:8088/$ACCESS_CODE/api/health"
  count=0
  while [ "$count" -lt 30 ]; do
    curl -fsS --max-time 2 "$url" >/dev/null 2>&1 && return 0
    count=$((count + 1)); sleep 1
  done
  return 1
}

rollback() {
  [ -f "$APP_DIR/family-learning.jar.previous" ] || return 1
  say "健康检查失败，恢复上一版本"
  cp -f "$APP_DIR/family-learning.jar.previous" "$APP_DIR/family-learning.jar"
  systemctl restart "$SERVICE"
  return 0
}

uninstall_app() {
  systemctl disable --now "$SERVICE" 2>/dev/null || true
  rm -f /etc/systemd/system/family-learning.service /usr/local/bin/family-learning "$NGINX_CONF"
  systemctl daemon-reload
  if nginx -t >/dev/null 2>&1; then systemctl reload nginx; fi
  say "程序已卸载；学习数据仍保留在 $DATA_DIR"
}

case "$ACTION" in
  install)
    preflight; install_packages; load_or_prompt_config; fetch_source; build_jar; write_env; migrate_json
    install_service; write_nginx_http; enable_https; configure_firewall
    health_check || { rollback; die "启动失败，请运行 journalctl -u $SERVICE"; }
    scheme=http; [ "${HTTPS_ACTIVE:-no}" = yes ] && scheme=https
    host=${DOMAIN:-${PUBLIC_IP:-服务器IP}}
    say "安装完成：$scheme://$host/$ACCESS_CODE/"
    say "初始管理员：admin / 123456，请登录后立即修改密码" ;;
  update)
    [ -f "$ENV_FILE" ] || die "尚未安装"; install_packages; load_or_prompt_config
    fetch_source; build_jar; write_env; migrate_json; install_service
    health_check || { rollback; die "更新失败，已尝试回滚"; }
    say "更新完成" ;;
  uninstall) uninstall_app ;;
  *) die "用法：install.sh {install|update|uninstall}" ;;
esac
