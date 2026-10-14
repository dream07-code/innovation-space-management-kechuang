#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
BACKEND_CONTEXT=${BACKEND_CONTEXT:-"$ROOT/backend"}
FRONTEND_CONTEXT=${FRONTEND_CONTEXT:-"$ROOT/frontend"}
BACKEND_IMAGE=${BACKEND_IMAGE:-innovation-space-backend:release-smoke}
FRONTEND_IMAGE=${FRONTEND_IMAGE:-innovation-space-frontend:release-smoke}
NETWORK=innovation-space-release-smoke
BACKEND_CONTAINER=innovation-space-release-backend-smoke
FRONTEND_CONTAINER=innovation-space-release-frontend-smoke
BACKEND_PORT=${BACKEND_PORT:-18081}
FRONTEND_PORT=${FRONTEND_PORT:-18080}

for file in \
  "$BACKEND_CONTEXT/Dockerfile" \
  "$FRONTEND_CONTEXT/Dockerfile" \
  "$ROOT/deploy/nginx.conf" \
  "$ROOT/deploy/docker-compose.yml"; do
  if [[ ! -f "$file" ]]; then
    echo "missing deployment artifact: $file" >&2
    exit 1
  fi
done

if [[ ! -f /tmp/innovation-release-env-20261013.sh ]]; then
  echo "missing local integration environment" >&2
  exit 1
fi

cleanup() {
  docker rm -f "$FRONTEND_CONTAINER" "$BACKEND_CONTAINER" >/dev/null 2>&1 || true
  docker network disconnect -f "$NETWORK" innovation-space-release-mysql-20261013 >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT
cleanup

docker build -f "$BACKEND_CONTEXT/Dockerfile" -t "$BACKEND_IMAGE" "$BACKEND_CONTEXT"
docker build -f "$FRONTEND_CONTEXT/Dockerfile" -t "$FRONTEND_IMAGE" "$FRONTEND_CONTEXT"
docker network create "$NETWORK" >/dev/null
docker network connect "$NETWORK" innovation-space-release-mysql-20261013 >/dev/null 2>&1 || true

set +u
source /tmp/innovation-release-env-20261013.sh
set -u
DB_HOST_VALUE=${DB_HOST_OVERRIDE:-innovation-space-release-mysql-20261013}
DB_PORT_VALUE=${DB_PORT_OVERRIDE:-3306}

docker run -d \
  --name "$BACKEND_CONTAINER" \
  --network "$NETWORK" \
  --network-alias backend \
  -p "127.0.0.1:${BACKEND_PORT}:8080" \
  -e SPRING_PROFILES_ACTIVE=prod \
  -e SERVER_ADDRESS=0.0.0.0 \
  -e SERVER_PORT=8080 \
  -e DB_HOST="$DB_HOST_VALUE" \
  -e DB_PORT="$DB_PORT_VALUE" \
  -e DB_NAME="$DB_NAME" \
  -e DB_USERNAME="$DB_USERNAME" \
  -e DB_PASSWORD="$DB_PASSWORD" \
  -e MEMBER_INITIAL_PASSWORD="$MEMBER_INITIAL_PASSWORD" \
  -e REPORT_POINTS_POLICY_STATUS=PENDING_CONFIRMATION \
  -e REPORT_POINTS_MODE=FINAL_SCORE \
  -e AI_SCORING_ENABLED=false \
  -e OJ_PROVIDER=mock \
  "$BACKEND_IMAGE" >/dev/null

for _ in $(seq 1 60); do
  if curl --max-time 3 -fsS "http://127.0.0.1:${BACKEND_PORT}/actuator/health" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

[[ "$(curl --max-time 3 -sS -o /tmp/release-smoke-health.json -w '%{http_code}' "http://127.0.0.1:${BACKEND_PORT}/actuator/health")" == 200 ]]
[[ "$(curl --max-time 3 -sS -o /tmp/release-smoke-doc.html -w '%{http_code}' "http://127.0.0.1:${BACKEND_PORT}/doc.html")" == 404 ]]
[[ "$(curl --max-time 3 -sS -o /tmp/release-smoke-openapi.json -w '%{http_code}' "http://127.0.0.1:${BACKEND_PORT}/v3/api-docs")" == 404 ]]

docker run -d \
  --name "$FRONTEND_CONTAINER" \
  --network "$NETWORK" \
  -p "127.0.0.1:${FRONTEND_PORT}:8080" \
  -v "$ROOT/deploy/nginx.conf:/etc/nginx/conf.d/default.conf:ro" \
  "$FRONTEND_IMAGE" >/dev/null

for _ in $(seq 1 30); do
  if curl --max-time 3 -fsS "http://127.0.0.1:${FRONTEND_PORT}/" >/dev/null 2>&1; then
    break
  fi
  sleep 1
done

[[ "$(curl --max-time 3 -sS -o /tmp/release-smoke-index.html -w '%{http_code}' "http://127.0.0.1:${FRONTEND_PORT}/")" == 200 ]]
[[ "$(curl --max-time 3 -sS -o /tmp/release-smoke-proxy-health.json -w '%{http_code}' "http://127.0.0.1:${FRONTEND_PORT}/actuator/health")" == 200 ]]
[[ "$(curl --max-time 3 -sS -o /tmp/release-smoke-login.json -w '%{http_code}' -X POST "http://127.0.0.1:${FRONTEND_PORT}/api/v1/auth/login" -H 'Content-Type: application/json' --data '{}')" == 400 ]]
[[ "$(curl --max-time 3 -sS -o /tmp/release-smoke-route.html -w '%{http_code}' "http://127.0.0.1:${FRONTEND_PORT}/reports/daily")" == 200 ]]
grep -qi 'content-security-policy' <(curl --max-time 3 -sSI "http://127.0.0.1:${FRONTEND_PORT}/")
grep -qi 'x-frame-options: deny' <(curl --max-time 3 -sSI "http://127.0.0.1:${FRONTEND_PORT}/")

echo "release deployment smoke passed"
