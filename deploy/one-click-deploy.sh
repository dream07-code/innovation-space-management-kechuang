#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE=${ENV_FILE:-"$ROOT/deploy/.env"}
COMPOSE_FILE="$ROOT/deploy/docker-compose.yml"
JUDGE0_DIR="$ROOT/deploy/judge0"
JUDGE0_COMPOSE="$JUDGE0_DIR/docker-compose.yml"
JUDGE0_CONF="$JUDGE0_DIR/judge0.conf"
OVERRIDE_FILE="$ROOT/deploy/docker-compose.override.yml"
OVERRIDE_MARKER="# 由 one-click-deploy.sh 自动生成（ENABLE_JUDGE0=true）。可删除，请勿手工编辑。"
MODE=${1:-deploy}

# override 文件可能在运行中被生成或移除，每次变更后必须重建 COMPOSE_FILES。
rebuild_compose_files() {
  COMPOSE_FILES=(-f "$COMPOSE_FILE")
  if [[ -f "$OVERRIDE_FILE" ]]; then
    COMPOSE_FILES+=(-f "$OVERRIDE_FILE")
  fi
}
rebuild_compose_files

usage() {
  cat <<'USAGE'
用法:
  ./deploy/one-click-deploy.sh [deploy|check|down]

  deploy  构建镜像、（默认）启动本机 Judge0 真实判题栈并接线、启动服务、
          初始化管理员并执行冒烟检查
  check   只校验 .env、Judge0 配置与 Docker Compose 配置（不启动容器）
  down    停止服务（保留 MySQL 与 Judge0 数据卷）

环境变量:
  ENV_FILE       指定环境文件，默认 deploy/.env
  ENABLE_JUDGE0  true（默认）= 一键部署本机 Judge0 并启用真实判题；
                 false = 不部署 Judge0，OJ 判题按 .env 的 OJ_PROVIDER 配置
USAGE
}

die() {
  echo "错误: $*" >&2
  exit 1
}

case "$MODE" in
  -h|--help|help)
    usage
    exit 0
    ;;
  deploy|check|down) ;;
  *)
    usage >&2
    die "未知模式: $MODE"
    ;;
esac

command -v docker >/dev/null 2>&1 || die "未安装 Docker"
if docker compose version >/dev/null 2>&1; then
  COMPOSE_BIN=(docker compose)
elif command -v docker-compose >/dev/null 2>&1; then
  COMPOSE_BIN=(docker-compose)
else
  die "未安装 Docker Compose（需要 docker compose 或 docker-compose）"
fi
command -v curl >/dev/null 2>&1 || die "未安装 curl"
[[ -f "$COMPOSE_FILE" ]] || die "缺少 $COMPOSE_FILE"
[[ -f "$ROOT/deploy/nginx.conf" ]] || die "缺少 $ROOT/deploy/nginx.conf"
[[ -f "$ENV_FILE" ]] || die "缺少环境文件 $ENV_FILE；先执行 cp deploy/.env.example deploy/.env 并填写私密值"

# shellcheck disable=SC1090
set -a
source "$ENV_FILE"
set +a

: "${DB_PASSWORD:?DB_PASSWORD 必须在 $ENV_FILE 中设置}"
: "${MYSQL_ROOT_PASSWORD:?MYSQL_ROOT_PASSWORD 必须在 $ENV_FILE 中设置}"
: "${MEMBER_INITIAL_PASSWORD:?MEMBER_INITIAL_PASSWORD 必须在 $ENV_FILE 中设置}"
: "${ADMIN_ACCOUNT:=admin}"
: "${ADMIN_PASSWORD:=}"
: "${ADMIN_DISPLAY_NAME:=学院管理员}"
: "${INIT_ADMIN:=true}"
: "${HTTP_PORT:=80}"
: "${BACKEND_IMAGE:=innovation-space-backend:local}"
: "${FRONTEND_IMAGE:=innovation-space-frontend:local}"
: "${ENABLE_JUDGE0:=true}"

[[ "$ADMIN_PASSWORD" != "replace-with-private-admin-password" ]] ||
  die "请在 $ENV_FILE 中设置真实 ADMIN_PASSWORD；脚本不会打印密码"
[[ -n "$ADMIN_PASSWORD" ]] || die "ADMIN_PASSWORD 不能为空"
[[ "$ADMIN_PASSWORD" != *$'\n'* ]] || die "ADMIN_PASSWORD 不能包含换行"

compose() {
  "${COMPOSE_BIN[@]}" --env-file "$ENV_FILE" "${COMPOSE_FILES[@]}" "$@"
}

judge0_compose() {
  "${COMPOSE_BIN[@]}" -f "$JUDGE0_COMPOSE" "$@"
}

rand_hex() {
  if command -v openssl >/dev/null 2>&1; then
    openssl rand -hex "$1"
  else
    head -c "$1" /dev/urandom | od -An -tx1 | tr -d ' \n'
  fi
}

# 生成带随机密钥的 judge0.conf（已存在则保持不动，密钥不轮换）。
ensure_judge0_conf() {
  [[ -f "$JUDGE0_CONF" ]] && return 0
  [[ -f "$JUDGE0_DIR/judge0.conf.example" ]] || die "缺少 $JUDGE0_DIR/judge0.conf.example"
  cp "$JUDGE0_DIR/judge0.conf.example" "$JUDGE0_CONF"
  sed -i "s|^AUTHN_TOKEN=.*|AUTHN_TOKEN=$(rand_hex 32)|" "$JUDGE0_CONF"
  sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(rand_hex 16)|" "$JUDGE0_CONF"
  sed -i "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$(rand_hex 16)|" "$JUDGE0_CONF"
  sed -i "s|^SECRET_KEY_BASE=.*|SECRET_KEY_BASE=$(rand_hex 64)|" "$JUDGE0_CONF"
  chmod 644 "$JUDGE0_CONF"
  echo "已生成 $JUDGE0_CONF（含随机密钥，已被 .gitignore 忽略）"
}

judge0_token() {
  grep '^AUTHN_TOKEN=' "$JUDGE0_CONF" | cut -d= -f2
}

# 把后端接入 judge0-link 外部网络；仅管理带标记的自动生成文件。
write_backend_override() {
  cat > "$OVERRIDE_FILE" <<OVERRIDE
$OVERRIDE_MARKER
services:
  backend:
    networks:
      - default
      - judge0-link

networks:
  judge0-link:
    external: true
    name: judge0-link
OVERRIDE
}

remove_generated_override() {
  if [[ -f "$OVERRIDE_FILE" ]] && [[ "$(head -n1 "$OVERRIDE_FILE")" == "$OVERRIDE_MARKER" ]]; then
    rm -f "$OVERRIDE_FILE"
    echo "ENABLE_JUDGE0=false：已移除自动生成的 $OVERRIDE_FILE"
  fi
  rebuild_compose_files
}

# 部署本机 Judge0 栈并把判题配置接线到业务后端（覆盖 .env 中的 OJ_* 判题项）。
setup_judge0() {
  ensure_judge0_conf
  docker network create judge0-link >/dev/null 2>&1 || true
  write_backend_override
  rebuild_compose_files

  echo "启动 Judge0 判题栈（首次启动含建库与语言种子，约 1 分钟）..."
  judge0_compose up -d

  local token
  token=$(judge0_token)
  local about_code=""
  for _ in $(seq 1 150); do
    about_code=$(curl --max-time 3 -sS -o /dev/null -w '%{http_code}' \
      -H "X-Auth-Token: $token" http://127.0.0.1:2358/about 2>/dev/null || true)
    [[ "$about_code" == "200" ]] && break
    sleep 1
  done
  [[ "$about_code" == "200" ]] || die "Judge0 健康检查失败，HTTP $about_code；查看日志: ${COMPOSE_BIN[*]} -f $JUDGE0_COMPOSE logs --tail=100 server"

  export OJ_PROVIDER=judge0
  export OJ_BASE_URL=http://judge0:2358
  export OJ_API_TOKEN="$token"
  export OJ_RLIMIT_FALLBACK=true
  echo "Judge0 已就绪，业务后端已接线真实判题（OJ_PROVIDER=judge0）"
}

if [[ "$MODE" == "check" ]]; then
  if [[ "$ENABLE_JUDGE0" == "true" ]]; then
    ensure_judge0_conf
    write_backend_override
    rebuild_compose_files
    judge0_compose config -q
  else
    remove_generated_override
  fi
  compose config -q
  echo "配置校验通过: $ENV_FILE"
  exit 0
fi

if [[ "$MODE" == "down" ]]; then
  compose down
  if [[ "$ENABLE_JUDGE0" == "true" ]] && [[ -f "$JUDGE0_COMPOSE" ]]; then
    judge0_compose down
    echo "Judge0 判题栈已停止，数据卷已保留"
  fi
  echo "服务已停止，MySQL 数据卷已保留"
  exit 0
fi

if [[ "$ENABLE_JUDGE0" == "true" ]]; then
  setup_judge0
else
  remove_generated_override
fi

if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
  # 构建缓存会长期占着 /data（日报图片也落在这块盘上），每次部署先清一次。
  # 只清构建缓存，不带 -a/--all：正在跑的镜像与容器不动。
  echo "清理 Docker 构建缓存（保留镜像与运行中的容器）..."
  docker builder prune -f >/dev/null 2>&1 || echo "构建缓存清理跳过（不影响部署）"
  compose build
fi

compose up -d

health_url="http://127.0.0.1:${HTTP_PORT}/actuator/health"
for _ in $(seq 1 90); do
  if curl --max-time 3 -fsS "$health_url" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

health_code=$(curl --max-time 3 -sS -o /tmp/innovation-health.json -w '%{http_code}' "$health_url" || true)
[[ "$health_code" == "200" ]] || die "后端健康检查失败，HTTP $health_code"

if [[ "$INIT_ADMIN" == "true" ]]; then
  compose run --rm --no-deps --entrypoint java \
    -e "ADMIN_ACCOUNT=$ADMIN_ACCOUNT" \
    -e "ADMIN_PASSWORD=$ADMIN_PASSWORD" \
    -e "ADMIN_DISPLAY_NAME=$ADMIN_DISPLAY_NAME" \
    backend -jar /app/app.jar --app.bootstrap.mode=admin
fi

index_code=$(curl --max-time 5 -sS -o /tmp/innovation-index.html -w '%{http_code}' "http://127.0.0.1:${HTTP_PORT}/" || true)
login_code=$(curl --max-time 15 -sS -o /tmp/innovation-login.json -w '%{http_code}' \
  -X POST "http://127.0.0.1:${HTTP_PORT}/api/v1/auth/login" \
  -H 'Content-Type: application/json' --data '{}' || true)
route_code=$(curl --max-time 5 -sS -o /tmp/innovation-route.html -w '%{http_code}' \
  "http://127.0.0.1:${HTTP_PORT}/reports/daily" || true)

[[ "$index_code" == "200" ]] || die "前端首页检查失败，HTTP $index_code"
[[ "$login_code" == "400" ]] || die "登录接口检查失败，HTTP $login_code"
[[ "$route_code" == "200" ]] || die "前端路由检查失败，HTTP $route_code"

echo "部署完成"
echo "访问地址: http://127.0.0.1:${HTTP_PORT}/"
echo "管理员账号: $ADMIN_ACCOUNT"
echo "OJ 判题: ${OJ_PROVIDER:-mock}$( [[ "${OJ_PROVIDER:-}" == "judge0" ]] && echo '（真实判题，自检: ./deploy/judge0/check.sh）' )"
echo "积分规则: ${REPORT_POINTS_POLICY_STATUS:-PENDING_CONFIRMATION}（交日报/周报计分开关）"
echo "停止服务: $0 down"
echo "配置检查: $0 check"
