# Judge0 判题服务部署

本目录是「学院科创空间管理系统」使用的 Judge0 独立部署栈，与业务应用分开管理。

## 组件

| 服务 | 镜像 | 说明 |
| --- | --- | --- |
| `server` | `judge0/judge0:1.13.1` | API，仅绑定宿主机 `127.0.0.1:2358` |
| `workers` | `judge0/judge0:1.13.1` | 判题 worker（`COUNT=1`，适配小内存机器） |
| `db` | `postgres:16.2` | Judge0 专用库，数据卷 `judge0-db-data` |
| `redis` | `redis:7.2.4` | 队列 |

数据库与 Redis 位于内部网络 `judge0_internal`（`internal: true`，无外网出口）。
业务后端通过外部网络 `judge0-link` 以服务名 `judge0` 访问 API。

## 首次部署

```bash
cd deploy/judge0

# 1. 业务网络（应用侧 docker-compose.override.yml 会引用它）
docker network create judge0-link 2>/dev/null || true

# 2. 生成配置（含密钥，已被 .gitignore 忽略）
cp judge0.conf.example judge0.conf
sed -i "s|^AUTHN_TOKEN=.*|AUTHN_TOKEN=$(openssl rand -hex 32)|" judge0.conf
sed -i "s|^POSTGRES_PASSWORD=.*|POSTGRES_PASSWORD=$(openssl rand -hex 16)|" judge0.conf
sed -i "s|^REDIS_PASSWORD=.*|REDIS_PASSWORD=$(openssl rand -hex 16)|" judge0.conf
sed -i "s|^SECRET_KEY_BASE=.*|SECRET_KEY_BASE=$(openssl rand -hex 64)|" judge0.conf
chmod 644 judge0.conf   # 容器内以非 root 用户读取

# 3. 启动
docker compose up -d

# 4. 验证（需要 AUTHN_TOKEN）
TOKEN=$(grep '^AUTHN_TOKEN=' judge0.conf | cut -d= -f2)
curl -H "X-Auth-Token: $TOKEN" http://127.0.0.1:2358/about
```

首次启动会执行数据库建表与 seed，约需 1 分钟。

## 应用侧配置

`deploy/.env`：

```dotenv
OJ_PROVIDER=judge0
OJ_BASE_URL=http://judge0:2358
OJ_API_TOKEN=<与 judge0.conf 的 AUTHN_TOKEN 一致>
OJ_RLIMIT_FALLBACK=true
```

`deploy/docker-compose.override.yml`（本地环境文件，未纳入仓库）需要把后端接入 `judge0-link`：

```yaml
services:
  backend:
    networks:
      - default
      - judge0-link

networks:
  judge0-link:
    external: true
    name: judge0-link
```

## 本机注意事项

- **cgroup v2**：本机没有 cgroup v1 的 `/sys/fs/cgroup/memory`，因此应用侧必须设置
  `OJ_RLIMIT_FALLBACK=true`，让每次提交都带上
  `enable_per_process_and_thread_time_limit` / `enable_per_process_and_thread_memory_limit`。
  否则 Judge0 会报 `Failed to create control group ...` 并返回状态 13（Internal Error）。
- **非 ASCII 文本**：应用侧已统一使用 `base64_encoded=true` 提交。若用 `false`，
  当代码/输出/期望答案含中文或全角符号时，Judge0 会返回
  `{"error": "... cannot be converted to UTF-8 ..."}` 且不含 `status` 字段。
- **防火墙**：若宿主机启用 firewalld，新建的 Docker 网桥会被拦截（表现为
  `No route to host`）。本栈已固定网桥名为 `judge0int0`，需要把它加入 `docker` 区：

  ```bash
  firewall-cmd --permanent --zone=docker --add-interface=judge0int0
  firewall-cmd --reload
  ```

## 运维

```bash
docker compose ps
docker compose logs -f server
docker compose down          # 停止（数据卷保留）
docker compose up -d         # 启动
```

Judge0 使用独立 compose 项目，业务应用的 `./deploy/one-click-deploy.sh down` 不会停止它。
