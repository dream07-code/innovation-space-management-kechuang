# 生产部署说明

本目录提供同源生产拓扑：Nginx 同时提供 Vue SPA、`/api` 业务代理和 `/actuator/health` 健康检查。Swagger、Knife4j 和其他 Actuator 端点不对外开放。

## 1. 前置条件

- Docker Engine 可用；使用 Compose 文件时需要 Docker Compose v2 插件。
- Java 21、Maven Wrapper、Node.js 24、pnpm 11 仅用于本地构建。
- 已准备 MySQL 备份/恢复方案、HTTPS 证书或上游 TLS 入口。
- 已确认 `REPORT_POINTS_POLICY_STATUS` 与 `REPORT_POINTS_MODE` 的正式业务规则。
- 已确认 MySQL 8.0 的 EOL 风险和升级计划。

## 2. 构建镜像

`main` 已包含前后端代码，在仓库根目录直接构建即可（一键脚本会自动完成）：

```bash
docker build -f backend/Dockerfile -t innovation-space-backend:local backend
docker build -f frontend/Dockerfile -t innovation-space-frontend:local frontend
```

镜像内不包含 `.env`、数据库密码、管理员密码、AI API Key 或 OJ Token。前端构建固定使用同源 `/api`，并显式关闭 MSW Mock。

## 3. 配置私密环境变量

```bash
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
```

必须替换：

- `DB_PASSWORD`
- `MYSQL_ROOT_PASSWORD`
- `MEMBER_INITIAL_PASSWORD`

不得提交 `deploy/.env`，也不要把密码写入 shell 历史、Dockerfile、Compose 文件或日志。

`REPORT_POINTS_POLICY_STATUS` 默认为 `PENDING_CONFIRMATION`，正式提交报告会返回规则未配置。只有学院确认最终 1–3 分规则后，才能设置为 `READY`。

## 4. 初始化数据库和管理员

先启动数据库：

```bash
docker compose up -d db
```

后端启动命令会自动执行受控 Flyway 迁移。管理员初始化是非 HTTP 维护命令，只执行一次：

```bash
export ADMIN_ACCOUNT='私密管理员账号'
read -rsp '管理员初始密码: ' ADMIN_PASSWORD; printf '\n'
export ADMIN_DISPLAY_NAME='学院管理员'
docker compose run --rm --no-deps --entrypoint java \
  -e ADMIN_ACCOUNT -e ADMIN_PASSWORD -e ADMIN_DISPLAY_NAME \
  backend -jar /app/app.jar --app.bootstrap.mode=admin
unset ADMIN_ACCOUNT ADMIN_PASSWORD ADMIN_DISPLAY_NAME
```

已有管理员不会被覆盖。学生和方向负责人由管理员或负责人通过业务接口添加，不提供用户注册。

## 5. 一键部署

在服务器仓库根目录先同步到目标版本，再执行：

```bash
git pull --ff-only
cp deploy/.env.example deploy/.env
chmod 600 deploy/.env
# 编辑 deploy/.env，设置数据库密码、成员初始密码和管理员密码
./deploy/one-click-deploy.sh
```

脚本默认执行：

1. 校验 Docker、Docker Compose、环境文件；
2. 构建后端和前端镜像；
3. 启动 MySQL、后端、前端；
4. 等待 `/actuator/health`；
5. 用 `deploy/.env` 中的 `ADMIN_ACCOUNT`、`ADMIN_PASSWORD`、`ADMIN_DISPLAY_NAME` 执行首个管理员初始化；
6. 检查首页、登录接口和前端路由；
7. 打印访问地址和停止命令。

管理员密码只放在本机忽略的 `deploy/.env` 中，不写入脚本、Git、日志或命令输出。首次部署建议将 `ADMIN_PASSWORD` 设置为学院指定的私密密码。

其他命令：

```bash
./deploy/one-click-deploy.sh check   # 只检查配置
./deploy/one-click-deploy.sh down    # 停止服务，保留 MySQL 数据卷
SKIP_BUILD=true ./deploy/one-click-deploy.sh  # 复用已有镜像
```

升级已有部署时无需手工迁移：后端启动命令会先执行 Flyway，再启动服务；本次评分规则升级（`rubricVersion=v2`）不涉及表结构变更，历史报告的旧分数不会被重算。AI 评分默认关闭，如需启用：`AI_SCORING_ENABLED=true`，并用 `AI_PROVIDER=heuristic`（离线近似，无需密钥）或 `openai-compatible`（真实模型，需 `AI_BASE_URL`/`AI_API_KEY`/`AI_MODEL`）。

当前默认 `OJ_PROVIDER=mock`。真实 Judge0 需要另行设置 `OJ_BASE_URL`、`OJ_API_TOKEN`，并在目标服务器完成判题验收。

判题返回“内部错误 / 0 个测试点通过 / 0 ms”表示 Judge0 没有真正执行代码，不是页面或评分问题。先在服务器运行判题自检：

```bash
./deploy/judge0/check.sh
```

脚本会依次检查 `/about`、默认 cgroup 模式判题和逐进程 rlimit 模式判题，并直接给出该开启哪个配置；最后还会提交一段 Java 代码，
验证当前隔离模式是否支持 JVM。

> 注意：cgroup v2 主机只能用 rlimit（`RLIMIT_AS`）模式，JVM 和 Node 启动时需要预留 1GB 以上虚拟地址空间，
> 因此 **Java、JavaScript 在该模式下必然编译/运行失败**（页面显示“编译错误”或“运行错误”，与代码无关）。
> 需要这两门语言时，请把 Judge0 迁到 cgroup v1 内核或改在独立的 cgroup v1 机器上部署，详见
> [deploy/judge0/README.md](judge0/README.md)。

## 6. 启动与检查

```bash
docker compose up -d
docker compose ps
curl -fsS http://127.0.0.1/actuator/health
```

预期：

- `/` 返回 Vue SPA。
- `/api/v1/...` 转发到后端。
- `/actuator/health` 返回健康状态。
- `/doc.html`、`/v3/api-docs` 和其他 Actuator 端点不可用或不泄露内部信息。
- SPA 深层路由返回 `index.html`。
- `/assets/*` 使用长期 immutable 缓存，`index.html` 使用 `no-store`。

自动化冒烟测试同时构建两个镜像并验证上述路由：

```bash
FRONTEND_CONTEXT=/path/to/frontend/checkout ./deploy/smoke.sh
```

## 7. TLS 与反向代理

生产域名必须使用 HTTPS。推荐在云负载均衡或前置 Nginx 终止 TLS，再转发到本 Compose 的 HTTP 入口。需要正确传递 `X-Forwarded-Proto` 和客户端 IP，并限制来源网络。

当前应用不使用 Cookie，认证 Token 仅保存在前端内存中；页面刷新会重新登录。不要为了绕过该行为把 Token 写入 localStorage。

## 8. 运维与安全

- 每日备份 MySQL，并定期在隔离环境执行恢复演练。
- 限制数据库账号权限，不允许应用账号执行 DROP、CREATE USER 或全局授权。
- AI 仅由后端调用；API Key 使用部署平台密钥管理，禁止写入 Git、数据库审计或日志。
- `OJ_PROVIDER=mock` 只用于开发。启用 Judge0 前必须完成隔离部署、私有测试数据保护和 GPL-3.0 合规审查。
- Ubuntu 24.04/cgroup v2 需要 `OJ_RLIMIT_FALLBACK=true`（或在 `judge0.conf` 允许逐进程 rlimit 字段）；该模式绕过旧 cgroup v1 控制组但降低资源隔离强度，必须在目标服务器完成超时、内存、编译错误和恶意代码样例验收。后端适配器在遇到 Judge0 状态 13 时会自动改用 rlimit 重试一次，并把已验证的模式复用到后续测试点，但仍建议显式配置 `OJ_RLIMIT_FALLBACK=true`，省掉每个新进程的第一次失败重试。
- 本地已验证 Judge0 1.13.1 可以启动 `/about`，但当前 cgroup v2 主机上的 isolate 作业无法创建 `/box`，因此默认仍使用 Mock；启用真实 Judge0 前必须在同等主机上完成 Python/Java 正确、超时、编译错误和无测试点样例验收，并确认 Judge0 不暴露公网、不共享业务 MySQL/Redis、不返回原始输出。
- 当前登录/改密限流是单进程限流。扩容到多实例前必须设计共享限流。
- 保持后端 `prod` 配置关闭 Swagger/Knife4j；关闭文档不是访问控制，公网入口仍需 TLS、网络策略和监控。
- MySQL 8.0 已进入 EOL。上线审批必须包含继续使用期限、补丁策略或升级方案。

## 9. 上线门槛

| 项目 | 要求 |
| --- | --- |
| 构建 | backend `clean verify` 和 frontend test/type-check/lint/build 全绿 |
| 数据库 | 已迁移、备份可恢复、最小权限验证通过 |
| 账号 | 管理员/负责人/学生私密交付和首次改密流程确认 |
| 积分规则 | `READY` 模式及 1–3 分解释由学院书面确认 |
| AI | 厂商、模型、Key、数据发送与保存策略确认 |
| OJ | Judge0 隔离部署、测试数据和 GPL 合规确认 |
| 网络 | HTTPS、域名、防火墙、监控告警和日志保留确认 |
| 回滚 | 上一版本镜像、数据库迁移回退/前滚方案已演练 |
