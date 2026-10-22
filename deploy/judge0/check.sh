#!/usr/bin/env bash
# Judge0 联通性与判题自检。
#
# 用法:
#   ./deploy/judge0/check.sh
#
# 环境变量:
#   JUDGE0_CONF   judge0 配置文件，默认同目录 judge0.conf（用于读取 AUTHN_TOKEN）
#   JUDGE0_URL    服务地址，默认 http://127.0.0.1:2358
#
# 退出码: 0 通过，1 判题失败，2 无法连通或鉴权失败。
set -Eeuo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CONF=${JUDGE0_CONF:-"$DIR/judge0.conf"}
URL=${JUDGE0_URL:-http://127.0.0.1:2358}
LANGUAGE_PYTHON=71

TOKEN=""
if [[ -f "$CONF" ]]; then
  TOKEN=$(grep -E '^AUTHN_TOKEN=' "$CONF" | head -1 | cut -d= -f2- || true)
fi
if [[ -z "$TOKEN" ]]; then
  echo "提示: 未从 $CONF 读到 AUTHN_TOKEN，本次请求不带鉴权头" >&2
fi

auth=()
if [[ -n "$TOKEN" ]]; then
  auth=(-H "X-Auth-Token: $TOKEN")
fi

echo "1) 检查 $URL/about"
about_code=$(curl -sS -o /dev/null -w '%{http_code}' "${auth[@]}" "$URL/about" || true)
if [[ "$about_code" != "200" ]]; then
  echo "   ✗ /about 返回 HTTP $about_code"
  echo "   排查: 容器是否启动（docker compose ps）、AUTHN_TOKEN 是否与 deploy/.env 的 OJ_API_TOKEN 一致、"
  echo "         业务后端是否接入 judge0-link 网络并能解析主机名 judge0"
  exit 2
fi
echo "   ✓ /about 可访问"

b64() { printf '%s' "$1" | base64 | tr -d '\n'; }

submit() {
  local rlimit=$1
  local payload
  if [[ "$rlimit" == "true" ]]; then
    payload=$(printf '{"source_code":"%s","language_id":%d,"stdin":"","expected_output":"%s","cpu_time_limit":2,"memory_limit":128000,"enable_per_process_and_thread_time_limit":true,"enable_per_process_and_thread_memory_limit":true}' \
      "$(b64 'print(1)')" "$LANGUAGE_PYTHON" "$(b64 $'1\n')")
  else
    payload=$(printf '{"source_code":"%s","language_id":%d,"stdin":"","expected_output":"%s","cpu_time_limit":2,"memory_limit":128000}' \
      "$(b64 'print(1)')" "$LANGUAGE_PYTHON" "$(b64 $'1\n')")
  fi
  curl -sS -w '\n%{http_code}' "${auth[@]}" -H 'Content-Type: application/json' \
    -d "$payload" "$URL/submissions?base64_encoded=true&wait=true" || true
}

status_of() {
  printf '%s' "$1" | tr -d '\n' | sed -n 's/.*"status":[[:space:]]*{[[:space:]]*"id":[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -1
}

for mode in false true; do
  if [[ "$mode" == "true" ]]; then label="逐进程 rlimit 模式"; else label="默认 cgroup 模式"; fi
  echo "2) 提交 print(1)（$label）"
  response=$(submit "$mode")
  http_code=$(printf '%s' "$response" | tail -1)
  body=$(printf '%s' "$response" | sed '$d')
  status=$(status_of "$body")

  if [[ "$http_code" == "401" ]]; then
    echo "   ✗ Judge0 拒绝鉴权（HTTP 401）"
    echo "   排查: judge0.conf 的 AUTHN_TOKEN 与 deploy/.env 的 OJ_API_TOKEN 必须完全一致，修改后需重建后端容器"
    exit 2
  fi
  if [[ "$http_code" != "200" && "$http_code" != "201" ]]; then
    echo "   ✗ 请求失败 HTTP $http_code: $(printf '%s' "$body" | head -c 200)"
    exit 2
  fi
  if [[ "$status" == "3" ]]; then
    echo "   ✓ 判题通过（status 3 Accepted，$label）"
    if [[ "$mode" == "true" ]]; then
      echo "   结论: 该主机必须使用 rlimit 模式，请在 deploy/.env 设置 OJ_RLIMIT_FALLBACK=true 并重建后端"
    fi
    exit 0
  fi
  echo "   ✗ 判题未通过，status=${status:-未知}"
  if [[ "$status" == "13" ]]; then
    echo "     状态 13 表示 Judge0 内部错误，cgroup v2 主机上通常是 isolate 无法创建 /box"
    if [[ "$mode" == "false" ]]; then
      echo "     继续尝试逐进程 rlimit 模式"
    fi
  fi
done

echo "3) 两种模式都失败"
echo "   继续排查: docker compose -f deploy/judge0/docker-compose.yml logs --tail=80 server workers"
echo "             docker compose logs --tail=80 backend"
echo "   若日志出现 'Failed to create control group' 或 '/box'，说明 isolate 与宿主 cgroup 不兼容，"
echo "   需要给 judge0 服务加 --privileged（已具备）并确认宿主机允许容器创建 cgroup，或改用支持 cgroup v2 的镜像。"
exit 1
