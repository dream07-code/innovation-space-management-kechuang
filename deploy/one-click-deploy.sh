#!/usr/bin/env bash
set -Eeuo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
ENV_FILE=${ENV_FILE:-"$ROOT/deploy/.env"}
COMPOSE_FILE="$ROOT/deploy/docker-compose.yml"
COMPOSE_FILES=(-f "$COMPOSE_FILE")
if [[ -f "$ROOT/deploy/docker-compose.override.yml" ]]; then
  COMPOSE_FILES+=(-f "$ROOT/deploy/docker-compose.override.yml")
fi
MODE=${1:-deploy}

usage() {
  cat <<'USAGE'
用法:
  ./deploy/one-click-deploy.sh [deploy|check|down]

  deploy  构建镜像、启动服务、初始化管理员并执行冒烟检查
  check   只校验 .env 与 Docker Compose 配置
  down    停止服务（保留 MySQL 数据卷）

环境变量:
  ENV_FILE  指定环境文件，默认 deploy/.env
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

[[ "$ADMIN_PASSWORD" != "replace-with-private-admin-password" ]] ||
  die "请在 $ENV_FILE 中设置真实 ADMIN_PASSWORD；脚本不会打印密码"
[[ -n "$ADMIN_PASSWORD" ]] || die "ADMIN_PASSWORD 不能为空"
[[ "$ADMIN_PASSWORD" != *$'\n'* ]] || die "ADMIN_PASSWORD 不能包含换行"

compose() {
  "${COMPOSE_BIN[@]}" --env-file "$ENV_FILE" "${COMPOSE_FILES[@]}" "$@"
}

if [[ "$MODE" == "check" ]]; then
  compose config -q
  echo "配置校验通过: $ENV_FILE"
  exit 0
fi

if [[ "$MODE" == "down" ]]; then
  compose down
  echo "服务已停止，MySQL 数据卷已保留"
  exit 0
fi

if [[ "${SKIP_BUILD:-false}" != "true" ]]; then
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
echo "停止服务: $0 down"
echo "配置检查: $0 check"
