# Judge0 判题服务部署

本目录是「AI 编程实训助手」使用的 Judge0 独立部署栈，与业务应用分开管理。

## 组件

| 服务 | 镜像 | 说明 |
| --- | --- | --- |
| `server` | `judge0/judge0:1.13.1` | API，仅绑定宿主机 `127.0.0.1:2358` |
| `workers` | `judge0/judge0:1.13.1` | 判题 worker（单容器默认 `COUNT=1`；并行度靠副本数扩展，见「并发扩展」） |
| `db` | `postgres:16.2` | Judge0 专用库，数据卷 `judge0-db-data` |
| `redis` | `redis:7.2.4` | 队列 |

数据库与 Redis 位于内部网络 `judge0_internal`（`internal: true`，无外网出口）。
业务后端通过外部网络 `judge0-link` 以服务名 `judge0` 访问 API。

## 首次部署

推荐直接使用仓库根目录的一键脚本（`ENABLE_JUDGE0=true` 时自动完成下方全部步骤：
生成 `judge0.conf` 密钥、创建网络、启动本栈、生成应用侧 override 并接线判题配置）：

```bash
cp deploy/.env.example deploy/.env && chmod 600 deploy/.env
# 编辑 deploy/.env 填写数据库与管理员密码
./deploy/one-click-deploy.sh
```

以下为手工部署等价步骤（一键脚本不可用时）：

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

# 5. 一键自检：API、鉴权、语言支持、真实判题（含非 ASCII 用例）
./check.sh
```

首次启动会执行数据库建表与 seed，约需 1 分钟。

## 并发扩展：6 路并行判题

判题吞吐由 **worker 副本数**决定，不是由 `COUNT` 决定。`judge0.conf` 里的 `COUNT=1`
只是单个容器内的默认值；实测把 `COUNT` 调大并不生效，不要依赖它。

扩展副本（线上采用的做法）：

```bash
cd deploy/judge0
docker compose up -d --scale workers=6        # 6 个 worker 并行取队列
docker compose up -d --scale workers=1        # 收回单副本
```

执行后 `docker compose ps` 应能看到 6 个 `workers` 容器；提交队列在 Redis 里，
应用侧不需要任何改动——后端仍然只调用 `server` 的 API。

一键脚本 `./deploy/one-click-deploy.sh` 只会起单 worker；需要 6 路并行时，
部署完成后在 `deploy/judge0` 补执行一次上面的 `--scale` 命令即可，不必重建后端。

不要给 `workers` 加 `container_name` 或 `ports`：有固定容器名就无法多副本，
映射端口也没必要（worker 只从内部队列取任务）。每个副本都是一个
`privileged` 容器，副本数要按宿主机内存定；cgroup v2 主机上仍需
`OJ_RLIMIT_FALLBACK=true`（见「本机注意事项」）。

## 应用侧配置

`deploy/.env`：

```dotenv
OJ_PROVIDER=judge0
OJ_BASE_URL=http://judge0:2358
OJ_API_TOKEN=<与 judge0.conf 的 AUTHN_TOKEN 一致>
OJ_RLIMIT_FALLBACK=true
OJ_REQUEST_TIMEOUT_MS=120000
```

`OJ_REQUEST_TIMEOUT_MS` 默认 30s。低配机器在负载高时单次判题可能超过 30s
（实测曾有 37s），超时会被记为 `INTERNAL_ERROR`，因此建议调到 60000–120000。

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

## 排障：提交后显示“内部错误”

页面出现 `内部错误`、通过用例 `0/N`、资源 `0 ms / 0 MB`，说明 Judge0 没有真正执行代码，按顺序排查：

1. 先运行自检脚本，它会直接给出结论：

   ```bash
   ./deploy/judge0/check.sh
   ```

2. `/about` 不通：容器未启动，或业务后端没接入 `judge0-link`。可用
   `docker network inspect judge0-link` 确认后端容器在列表中；缺失时按上文“应用侧配置”补
   `docker-compose.override.yml` 并重建后端。
3. HTTP 401：`judge0.conf` 的 `AUTHN_TOKEN` 与 `deploy/.env` 的 `OJ_API_TOKEN` 不一致。
   修改后必须 `docker compose up -d backend` 让容器读到新值。
4. HTTP 422 且错误里出现 `enable_per_process_and_thread_time_limit`：judge0 未允许逐进程
   rlimit 字段，在 `judge0.conf` 增加并重启 judge0：

   ```dotenv
   ALLOW_ENABLE_PER_PROCESS_AND_THREAD_TIME_LIMIT=true
   ALLOW_ENABLE_PER_PROCESS_AND_THREAD_MEMORY_LIMIT=true
   ```

5. 判题状态 13，或 judge0 日志出现 `Failed to create control group` / `/box`：cgroup v2 主机上
   isolate 无法创建控制组。在 `deploy/.env` 设置 `OJ_RLIMIT_FALLBACK=true` 并重建后端；
   后端适配器也会在首次遇到状态 13 时自动改用 rlimit 重试。
6. 查看后端日志确认原因（只包含状态码和字段名，不含源码）：

   ```bash
   docker compose logs --tail=100 backend | grep -i judge0
   ```

## Java / JavaScript / Go 在 rlimit 模式下的处理

cgroup v2 主机上只能启用 `OJ_RLIMIT_FALLBACK=true`，isolate 用 `-m`（`RLIMIT_AS`）
限制虚拟地址空间。C/C++/Python/Rust 的进程只占几十 MB 虚拟内存，可以直接运行；
**JVM 和 Node 启动时要预留 1GB 以上虚拟地址空间**（压缩类空间、堆、代码缓存），
默认参数下 `javac` 会报 `Error occurred during initialization of VM /
Could not reserve enough space for object heap`，Judge0 返回状态 6（页面显示“编译错误”），
与提交的代码无关。

仓库已内置处理方案，三处配置配合：

1. **`seeds.rb`**（已挂载到 `/api/db/seeds.rb`）：Judge0 每次启动都会 `rails db:seed`，
   这份种子在创建语言后，为 Java 的 `javac`/`java` 加上 `-Xmx128m`、
   `-XX:CompressedClassSpaceSize=64m` 等参数，为 Node 加上 `--max-old-space-size=128`。
   真实堆仍然很小，只是不再默认预留 1GB 类空间。
2. **`MAX_MEMORY_LIMIT=1600000`**：编译阶段用这个值作为 isolate 的内存上限，必须足够 JVM 启动。
   `docker-compose.yml` 已把它作为默认环境变量注入（judge0.conf 显式写了同名项时以文件为准），
   新部署无需再手改；老部署若 `judge0.conf` 里写了 `MAX_MEMORY_LIMIT=512000`，要改成 1600000。
3. **应用侧** `OJ_RUNTIME_MEMORY_LIMIT_KB=1500000`：后端对 java/javascript/go 使用这个
   虚拟内存上限（C/C++/Python/Rust 仍用 128MB），该值必须不大于 `MAX_MEMORY_LIMIT`。

生效步骤：

```bash
docker compose -f deploy/judge0/docker-compose.yml up -d server   # 重新 seed 语言命令
cd ../.. && ./deploy/one-click-deploy.sh                          # 重建后端读取新的内存上限
./deploy/judge0/check.sh                                          # Java 探针应通过
```

升级 Judge0 镜像时要复核 `seeds.rb`：它复刻了 1.13.1 的 `db/seeds.rb` 结构，
上游若有变化需要同步，否则 `db:seed` 会失败。

如果希望彻底回到 cgroups 的真实内存限制（不再需要上述参数），可以把 Judge0 迁移到
cgroup v1 内核（`GRUB_CMDLINE_LINUX` 追加 `systemd.unified_cgroup_hierarchy=0` 后重启），
再把 `OJ_RLIMIT_FALLBACK` 改回 `false`。

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
docker compose up -d         # 启动（单 worker）
docker compose up -d --scale workers=6   # 并行判题，见「并发扩展」
```

Judge0 使用独立 compose 项目。`./deploy/one-click-deploy.sh down` 在 `ENABLE_JUDGE0=true` 时会一并停止本栈（数据卷保留）；`ENABLE_JUDGE0=false` 的部署仍需按本节命令手工管理。
