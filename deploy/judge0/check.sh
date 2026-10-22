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
# 退出码: 0 判题可用，1 判题失败，2 无法连通或鉴权失败。
set -Eeuo pipefail

DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
CONF=${JUDGE0_CONF:-"$DIR/judge0.conf"}
URL=${JUDGE0_URL:-http://127.0.0.1:2358}
LANGUAGE_PYTHON=71
LANGUAGE_JAVA=62
RUNTIME_MEMORY=${OJ_RUNTIME_MEMORY_LIMIT_KB:-1500000}

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

# submit <language_id> <source> <expected_output> <rlimit:true|false> <memory_kb>
# 输出: 第一行 body，最后一行 HTTP 状态码
submit() {
  local language=$1 source=$2 expected=$3 rlimit=$4 memory=${5:-128000}
  local payload
  if [[ "$rlimit" == "true" ]]; then
    payload=$(printf '{"source_code":"%s","language_id":%d,"stdin":"","expected_output":"%s","cpu_time_limit":2,"memory_limit":%d,"enable_per_process_and_thread_time_limit":true,"enable_per_process_and_thread_memory_limit":true}' \
      "$(b64 "$source")" "$language" "$(b64 "$expected")" "$memory")
  else
    payload=$(printf '{"source_code":"%s","language_id":%d,"stdin":"","expected_output":"%s","cpu_time_limit":2,"memory_limit":%d}' \
      "$(b64 "$source")" "$language" "$(b64 "$expected")" "$memory")
  fi
  curl -sS -w '\n%{http_code}' "${auth[@]}" -H 'Content-Type: application/json' \
    -d "$payload" "$URL/submissions?base64_encoded=true&wait=true" || true
}

status_of() {
  printf '%s' "$1" | tr -d '\n' | sed -n 's/.*"status":[[:space:]]*{[[:space:]]*"id":[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -1
}

# probe <language_id> <source> <expected> <rlimit> <label> [memory_kb]
# 返回 0 表示 Accepted，非 0 为判题状态码或 1（非判题失败）
probe() {
  local language=$1 source=$2 expected=$3 rlimit=$4 label=$5 memory=${6:-128000}
  local response http_code body status
  response=$(submit "$language" "$source" "$expected" "$rlimit" "$memory")
  http_code=$(printf '%s' "$response" | tail -1)
  body=$(printf '%s' "$response" | sed '$d')
  status=$(status_of "$body")
  PROBE_BODY=$body
  PROBE_HTTP=$http_code
  PROBE_STATUS=$status
  echo "   · $label -> HTTP $http_code, status=${status:-未知}"
  [[ "$status" == "3" ]]
}

python_code='print(1)'
python_expected=$'1\n'

working_mode=""
for mode in false true; do
  if [[ "$mode" == "true" ]]; then label="逐进程 rlimit 模式"; else label="默认 cgroup 模式"; fi
  echo "2) 提交 print(1)（$label）"
  if probe "$LANGUAGE_PYTHON" "$python_code" "$python_expected" "$mode" "$label"; then
    working_mode=$mode
    echo "   ✓ Python 判题通过"
    break
  fi
  if [[ "$PROBE_HTTP" == "401" ]]; then
    echo "   ✗ Judge0 拒绝鉴权（HTTP 401）"
    echo "   排查: judge0.conf 的 AUTHN_TOKEN 与 deploy/.env 的 OJ_API_TOKEN 必须完全一致，修改后需重建后端容器"
    exit 2
  fi
  if [[ "$PROBE_HTTP" == "422" ]]; then
    echo "   ✗ Judge0 拒绝了请求参数（HTTP 422）"
    echo "   排查: judge0.conf 的 MAX_MEMORY_LIMIT 是否 >= deploy/.env 的 OJ_RUNTIME_MEMORY_LIMIT_KB，"
    echo "         并确认 judge0.conf 允许 enable_per_process_and_thread_* 字段"
    exit 2
  fi
  if [[ "$PROBE_HTTP" != "200" && "$PROBE_HTTP" != "201" ]]; then
    echo "   ✗ 请求失败 HTTP $PROBE_HTTP: $(printf '%s' "$PROBE_BODY" | head -c 200)"
    exit 2
  fi
  if [[ "$PROBE_STATUS" == "13" ]]; then
    echo "     状态 13 表示 Judge0 内部错误，cgroup v2 主机上通常是 isolate 无法创建 /box"
    if [[ "$mode" == "false" ]]; then
      echo "     继续尝试逐进程 rlimit 模式"
    fi
  fi
done

if [[ -z "$working_mode" ]]; then
  echo "3) Python 在两种模式下都失败"
  echo "   继续排查: docker compose -f deploy/judge0/docker-compose.yml logs --tail=80 server workers"
  echo "             docker compose logs --tail=80 backend"
  echo "   若日志出现 'Failed to create control group' 或 '/box'，说明 isolate 与宿主 cgroup 不兼容。"
  exit 1
fi

if [[ "$working_mode" == "true" ]]; then
  echo "   结论: 该主机必须使用 rlimit 模式，请在 deploy/.env 设置 OJ_RLIMIT_FALLBACK=true 并重建后端"
fi

# Java 在 rlimit 模式下的已知限制：JVM 需要预留 1GB 以上的虚拟地址空间，
# 而 rlimit 用 RLIMIT_AS 限制内存，javac/java 会直接启动失败（表现为编译错误）。
echo "4) 检查 Java（language 62）"
java_source='public class Main { public static void main(String[] args) { System.out.print(1); } }'
if probe "$LANGUAGE_JAVA" "$java_source" "1" "$working_mode" "Java 13" "$RUNTIME_MEMORY"; then
  echo "   ✓ Java 判题通过"
else
  echo "   ✗ Java 未通过（status=${PROBE_STATUS:-未知}），按顺序检查："
  echo "     1) deploy/judge0/docker-compose.yml 是否把 ./seeds.rb 挂载到 /api/db/seeds.rb"
  echo "        （seeds.rb 给 javac/java 加了 -Xmx 等参数，没有它 JVM 无法在 RLIMIT_AS 下启动）"
  echo "     2) judge0.conf 的 MAX_MEMORY_LIMIT 是否 >= deploy/.env 的 OJ_RUNTIME_MEMORY_LIMIT_KB"
  echo "     3) 改完后重启 judge0 server，让启动时的 db:seed 重新写入语言命令："
  echo "        docker compose -f deploy/judge0/docker-compose.yml up -d server"
  echo "     4) 仍失败时可把 Judge0 迁到 cgroup v1 内核（GRUB 加 systemd.unified_cgroup_hierarchy=0）"
  if [[ "$PROBE_STATUS" == "6" ]]; then
    echo "     后端日志会给出具体编译错误：docker compose logs --tail=50 backend | grep -i judge0"
  fi
fi

exit 0
