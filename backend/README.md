# 后端基础工程

已实现后端基础设施以及登录/首次改密、数据库Bearer会话、方向范围授权、人员和负责人管理、学号更正/密码重置与审计，并完成日报周报、可配置 AI 评分、积分流水、排行榜和 Knife4j 契约接口。月度评定和月度淘汰已取消；连续14个完整自然日未提交日报自动移出及学院管理员恢复已实现。学生不能自行注册，只能由方向负责人或学院管理员添加。

## 技术栈与边界

- Java 21、Spring Boot 4.1.1、MyBatis-Plus 3.5.17。
- Maven Wrapper 3.9.16；可直接使用仓库中的 `mvnw`，不必安装全局 Maven。
- MySQL 8.0.46；测试固定镜像摘要，不用 H2 代替。
- springdoc 3.1.1 生成 OpenAPI 3.0，Knife4j 4.5.0 独立 UI；没有引入 Boot 3 的 Knife4j Starter。
- 业务时钟 `Asia/Shanghai`，JDBC 连接会话 `UTC`。
- API 基础响应 `code/message/data`，安全异常映射保留 HTTP 状态码。
- 分页全局上限 200 条，小于上限的请求级限制也生效；在总页数检查前规范化。
- 默认Web启动不执行DDL；明确迁移模式通过Flyway建本阶段表和七方向。没有默认管理员或学生数据。

## 环境变量

从本目录执行下述命令。`.env.example` 只用于说明，**Spring Boot 不自动读取 `.env`**；请显式导出环境变量或使用部署平台的密钥管理。

| 变量 | 默认值/说明 |
| --- | --- |
| `DB_HOST` | `127.0.0.1` |
| `DB_PORT` | `3306` |
| `DB_NAME` | `innovation_space` |
| `DB_USERNAME` | 必填且非空，不默认使用 root |
| `DB_PASSWORD` | 必填且非空，不提供默认密码 |
| `SERVER_ADDRESS` | `127.0.0.1`，默认不开放公网访问 |
| `SERVER_PORT` | `8080` |
| `SPRING_PROFILES_ACTIVE` | 未设置时为默认环境，文档关闭；开发设为 `dev` |
| `MEMBER_INITIAL_PASSWORD` | 创建/重置成员时必填的私密共享初始口令；不在仓库提供默认值 |

没有凭据时应用启动失败；配置凭据但数据库不可用时，健康检查返回 `503`/`DOWN`，不返回虚假的 `UP`。不将密码放在 Java 命令行、提交文件或日志中。

## 独立开发数据库（Docker CLI，无需 Compose）

需要已运行的 Docker Engine 及当前用户访问 Docker 的权限。不要重置或复用含已有业务数据的数据库。以下示例创建**新开发库**，主机端口为 3307，避免占用本机已有的 3306；应用使用非 root 用户。

```bash
export DB_HOST=127.0.0.1 DB_PORT=3307 DB_NAME=innovation_space DB_USERNAME=innovation_app
read -rsp '开发应用数据库密码: ' DB_PASSWORD; printf '\n'; export DB_PASSWORD
read -rsp '新容器 root 密码（仅初始化用）: ' MYSQL_ROOT_PASSWORD; printf '\n'; export MYSQL_ROOT_PASSWORD
export MYSQL_DATABASE="$DB_NAME" MYSQL_USER="$DB_USERNAME" MYSQL_PASSWORD="$DB_PASSWORD"

docker run -d --name innovation-space-dev-mysql \
  -p 127.0.0.1:3307:3306 \
  --mount type=volume,source=innovation-space-dev-mysql,target=/var/lib/mysql \
  --env MYSQL_ROOT_PASSWORD --env MYSQL_DATABASE --env MYSQL_USER --env MYSQL_PASSWORD \
  mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b

# 等待新容器初始化完毕；仅检查自己新建的容器。
docker exec innovation-space-dev-mysql mysqladmin ping --silent
unset MYSQL_ROOT_PASSWORD MYSQL_PASSWORD MYSQL_DATABASE MYSQL_USER
```

若已有独立、空的开发库，可直接导出相应 `DB_*`，不用启动第二个 MySQL。示例 Docker 数据卷在容器停止后保留开发数据；测试容器则一次性销毁。不要执行全局 prune 或清空现有数据库。

## 构建与启动

```bash
java -version                  # 必须为 Java 21
./mvnw -version                # Wrapper 自动获取指定 Maven
./mvnw test                    # 单元测试，不需要 Docker
./mvnw clean verify            # 单元 + 真实 MySQL 集成 + 正式 JAR 测试，需要 Docker

export SPRING_PROFILES_ACTIVE=dev
# 必须先注入上节的 DB_*。
java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar
# 开发时也可用 ./mvnw spring-boot:run
```

`package` 生成可执行 JAR，但不代表已执行全部集成验收；完整验收使用 `verify`。Docker 不可用时集成测试应失败，不会默默跳过。

| 环境 | `/actuator/health` | `/doc.html`、OpenAPI JSON、文档静态资源 |
| --- | --- | --- |
| 默认 | 数据库可用时 `200`/`UP` | `404` |
| `dev` | 数据库可用时 `200`/`UP` | 可访问 |
| `prod` | 数据库可用时 `200`/`UP` | `404` |
| 同时设置 `dev` 和 `prod`（任何顺序） | 不受文档开关影响 | `prod` 优先，关闭 |

默认地址：健康检查 `http://127.0.0.1:8080/actuator/health`；开发文档 `http://127.0.0.1:8080/doc.html`。分组 `innovation-space` 的契约在 `/v3/api-docs/innovation-space`，配置在 `/v3/api-docs/swagger-config`。

正式JAR开发文档只显示已注册的业务API，描述/DTO依据已审阅的契约；实际HTTP响应另有契约断言。月评等已取消路径和手动提前移出等未实现路径不会显示为可用。测试探针不进入正式JAR，不提供自注册接口。健康原生JSON，不公开数据库细节或其他Actuator端点；数据库已连通但尚未迁移仍为DOWN/503。

## 验证与浏览器联调

测试覆盖凭据缺失、异常与校验、真实 MySQL/Mapper、UTC、分页、健康 `UP/DOWN`、默认/dev/prod 文档和真实静态资源、测试代码的 JAR 排除，以及正式 JAR 的各环境启动。集成上下文和浏览器探针使用仅测试的显式连接池，防止宿主机 Hikari 专用连接属性或初始化 SQL 接管测试库；双容器 decoy 回归验证非目标数据库不被修改，探针写入前也检查实际 JDBC URL。正式 JAR 验证仍使用真实的生产 Boot 数据源自动配置。

如需复现测试探针下的 Knife4j 页面调试，另开终端，在本目录执行：

```bash
./mvnw test-compile dependency:build-classpath -Dmdep.outputFile=target/test-classpath.txt
java -cp "target/test-classes:target/classes:$(cat target/test-classpath.txt)" \
  io.github.dream07code.testsupport.BrowserSmokeApplication
```

该测试启动器自建一次性 MySQL，监听随机本地端口；`target/browser-smoke.json` 只记录 `baseUrl`。访问其 `/doc.html`，在“仅测试探针”中调试 GET ping，应得到 `200` 和 `{"code":"OK","message":"Success","data":"pong"}`。用 Ctrl+C 正常退出，关闭其上下文和容器。测试日志、截图及类路径文件放在 Git 忽略的 `target/` 内，不提交。

可选自动验收：安装 Node.js、Playwright 及其 Chromium 后，在本目录执行 `node src/test/browser/knife4j-smoke.cjs`。如 Playwright 位于外部工具环境，先把 `PLAYWRIGHT_MODULE` 设置为其模块绝对路径。该脚本从**真实页面**点击调试/发送，断言响应及 JS/网络错误，并输出 `target/verification/knife4j-dev.png` 和 JSON 结果；它不属于 Maven 的 Java 测试计数。

## 上线前必须补齐

当前后端已完成本阶段报告/积分功能；上线前仍需确认账号私密交付、隐私保护、限流部署策略与备份恢复，不能因为已有认证就跳过业务确认。

生产镜像、同源 Nginx、Compose、环境变量和冒烟验证见 [`deploy/README.md`](../deploy/README.md)。

## 认证与业务基础接口（backend 当前实现）

当前 `backend` 已实现并通过真实 MySQL 集成测试的接口包括：

- `POST /api/v1/auth/login`、`POST /api/v1/auth/refresh`、`POST /api/v1/auth/logout`、`GET /api/v1/auth/me`、`PUT /api/v1/auth/password`。
- `GET /api/v1/directions`、`GET /api/v1/directions/{directionId}`。
- 管理员/方向负责人范围内的 `GET/POST /api/v1/members`、`GET/PATCH /api/v1/members/{memberId}`。
- 方向负责人任命、维护和撤销；管理员学号更正、密码重置和脱敏审计查询。

业务接口必须先执行受控数据库迁移；普通 Web 启动不会自动改库：

```bash
# 先注入 DB_*，目标必须是明确的独立开发库或部署库。
java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar --app.migration.mode=migrate
```

也可以直接使用可执行 JAR 加 `--app.migration.mode=migrate`；该模式只执行 Flyway、不启动 HTTP。迁移成功后再按普通方式启动服务。没有迁移的数据库健康为 `DOWN/503`，业务请求为 `503 SERVICE_UNAVAILABLE`，不会自动建表。

管理员初始化是非 HTTP 维护模式，要求数据库已迁移。只在受控环境临时导出 `ADMIN_ACCOUNT`、`ADMIN_PASSWORD`、`ADMIN_DISPLAY_NAME`，然后运行 `--app.bootstrap.mode=admin`；缺少变量会失败，已有管理员不会被覆盖，输出不会回显密码或哈希。不要把这些变量写入 `.env`、脚本或 Git。

学生和负责人使用学号登录；学生不能自注册。日报、周报、AI 评分、积分、排行榜、14个完整自然日自动移出和管理员恢复已实现；月度评定/淘汰已取消。

### 报告、评分与积分（进行中）

已实现日报/周报草稿、查询、合并 PATCH、提交、报告版本基础、可插拔 Mock/OpenAI-compatible 评分 Provider、异步评分 Worker、评分任务和积分流水表，以及个人积分、积分流水、总榜、方向榜和个人排名接口。报告列表先按学生/负责人/管理员范围过滤；评分 Worker 会按版本校验后补 AI 差额积分，旧版本迟到结果只标记 STALE，人工结果优先；提交状态和评分状态相互独立。

外部模型仅从私密环境配置注入：

```text
AI_SCORING_ENABLED=false
AI_PROVIDER=disabled|mock|openai-compatible
AI_BASE_URL=
AI_API_KEY=
AI_MODEL=
AI_MAX_RETRIES=2
AI_MAX_CONCURRENCY=2
AI_POLL_INTERVAL_MS=1000
AI_WORKER_SCHEDULING_ENABLED=true
REPORT_POINTS_POLICY_STATUS=PENDING_CONFIRMATION
REPORT_POINTS_MODE=FINAL_SCORE
REPORT_MAX_FINAL_SCORE=3
MEMBERSHIP_AUTO_REMOVAL_ENABLED=true
MEMBERSHIP_REMOVAL_CHECK_INTERVAL_MS=3600000
MEMBERSHIP_REMOVAL_BATCH_SIZE=500
```

默认关闭，未配置 Key 不调用外部服务；`AI_WORKER_SCHEDULING_ENABLED=true
REPORT_POINTS_POLICY_STATUS=PENDING_CONFIRMATION
REPORT_POINTS_MODE=FINAL_SCORE
REPORT_MAX_FINAL_SCORE=3
MEMBERSHIP_AUTO_REMOVAL_ENABLED=true
MEMBERSHIP_REMOVAL_CHECK_INTERVAL_MS=3600000
MEMBERSHIP_REMOVAL_BATCH_SIZE=500` 且评分开启时才启动有界并发的调度器。真实 Key、Authorization、姓名、学号和联系方式不写入数据库、Git、日志、审计或响应；报告正文只保存在受控的 report_version.content_json，不进入任务、日志或模型响应记录。公共 HTTP base URL 必须 HTTPS；仅 loopback 测试允许 HTTP。

学院管理员可调用 `POST /api/v1/members/{memberId}/points`，请求仅包含正整数 `points`，不需要原因、version 或 Idempotency-Key；每次调用写入一条 `MANUAL_ADJUSTMENT` 流水并累计，不支持减分。

积分模式和报告提交保底规则必须通过 `REPORT_POINTS_POLICY_STATUS=READY` 与 `REPORT_POINTS_MODE` 明确确认；支持 `FINAL_SCORE` 和 `BASE_PLUS_AI`，`PENDING_CONFIRMATION` 时正式提交返回 `RULES_NOT_CONFIGURED`。月度评定/淘汰不再实现。自动移出按 `Asia/Shanghai` 的14个完整自然日执行，历史报告和原始流水保留，净积分通过 `REVERSAL` 清零；管理员恢复后开始新观察期。

会话使用32字节随机不透明token，库内只存SHA-256摘要；密码采用Spring Security PBKDF2-HMAC-SHA256、16字节盐、600000迭代、256位输出。首次/重置后只发300秒受限access，不能refresh或调用业务，改密后重新登录。普通access900秒，refresh家族绝对7天，刷新轮换且旧token重放撤销家族；前端以响应expiresIn为准。改密/重置/学号更正/授权或状态变化立即撤销相关会话。

登录/刷新/改密均为单进程有界固定窗口限流（60秒10次）；429含Retry-After，不信任未配置代理的X-Forwarded-For。当前无Cookie认证、默认管理员或JSESSIONID，跨域默认不开放。多实例需要另行设计全局限流；共享初始密码也不能替代身份交付核验。默认TLS由部署入口负责。

用户指定的 MySQL 8.0 已于 2026 年 4 月进入 EOL 阶段，继续按已确认的版本完成开发验证，但上线前必须重新评估支持周期、安全修复和升级方案；参考已确认的技术设计及 MySQL 官方生命周期公告。默认及 `prod` 文档关闭不是认证方案，公网部署仍需要认证、TLS、受支持的数据库版本、最小权限和密钥管理。

## OJ 考核与前端并行联调

OJ 考核接口先以 `planned` OpenAPI 契约发布，前端可开发考核/题库/代码编辑器/提交状态/AI 与人工评分/独立结果页面，并按 `x-frontend-contract` 的 Mock 工作流和角色矩阵处理状态与错误码。契约中的 ID 均为十进制字符串；`code` 只在创建提交请求中出现，普通提交、结果、AI 评估和人工批阅响应不返回代码正文、私有测试数据、OJ 原始 stdout/stderr 或模型原始输出。

当前默认 `OJ_PROVIDER=mock`。选型报告见 `docs/superpowers/reports/2026-10-12-open-source-oj-selection.md`：Judge0 适配器已实现并用本地 fake HTTP contract tests 验证，但真实启用前必须部署隔离的 Judge0 服务、提供受控私有测试用例加载器，并完成 GPL-3.0 合规审查。`OJ_PROVIDER=judge0` 时仅服务端访问 `OJ_BASE_URL`/`OJ_API_TOKEN`，浏览器不能调用 OJ 私有 API。

### Judge0 本地验证

已在开发机尝试启动隔离的 `judge0/judge0:latest`，并用独立 PostgreSQL、Redis 和 privileged 容器验证 `/about` 返回 `1.13.1`。当前主机使用 cgroup v2，Judge0 1.13.1 的 isolate 作业会返回 `INTERNAL_ERROR`（`/box/script.py` 不可创建），因此生产判题仍保持 `OJ_PROVIDER=mock` 降级；这不代表应用适配器故障，fake HTTP contract 已覆盖成功、失败、超时、非法状态和无测试点场景。

需要重新验证时，使用隔离网络、专用数据库和不暴露公网的 loopback 端口：

```bash
docker network create innovation-judge0-net
docker run -d --name innovation-judge0-postgres --network innovation-judge0-net \
  -e POSTGRES_PASSWORD='<一次性开发密码>' postgres:13-alpine
docker run -d --name innovation-judge0-redis --network innovation-judge0-net redis:6-alpine
docker run -d --name innovation-judge0 --network innovation-judge0-net --privileged \
  -p 127.0.0.1:2358:2358 \
  -e POSTGRES_HOST=innovation-judge0-postgres \
  -e POSTGRES_PASSWORD='<一次性开发密码>' \
  -e REDIS_HOST=innovation-judge0-redis judge0/judge0:latest
curl -fsS http://127.0.0.1:2358/about
```

只有在 `/about` 健康且真实 Python/Java 样例能稳定返回 `ACCEPTED`、cgroup 隔离和磁盘配额经过验收后，才能设置 `OJ_PROVIDER=judge0`。Judge0 Token 只能通过服务端密钥管理注入；浏览器、Git、审计和日志不得接触 Token、原始 stdout/stderr 或正式考核隐藏测试点。

AI 评分支持 `mock` 和 `openai-compatible` 两种服务端 Provider，厂商兼容端点、模型和 API Key 通过 `AI_BASE_URL`、`AI_MODEL`、`AI_API_KEY` 注入。人工 review 覆盖 AI 结果；AI 待定/失败时 `finalScore=null` 且 `scoreSource=PENDING/UNAVAILABLE`。OJ 结果不写入 `point_ledger`，不改变日报/周报积分或排行榜。积分排行榜及排名人数仅统计 `ACTIVE` 学生；方向负责人保留报告积分和流水，但不进入总榜、方向榜或个人排名，前端隐藏负责人的“我的排名”。

## 角色化数据看板

首页数据看板按角色提供独立的只读聚合接口：

- `GET /api/v1/dashboard/admin?range=7d|30d`：学院管理员，管理员默认 `30d`，包含人员、报告、评分、积分、方向对比、OJ 和脱敏待办。
- `GET /api/v1/dashboard/leader?range=7d|30d`：方向负责人，默认 `7d`，只统计获授权方向；负责人不进入学生排行榜。
- `GET /api/v1/dashboard/student?range=7d|30d`：学生，默认 `7d`，只返回本人积分、排名、报告、OJ、积分流水和进度。

三个接口都只读，不修改积分、报告、成员或 OJ 状态；响应不包含学号、联系方式、报告全文、代码、私有测试数据、模型原始输出或 Token。前端布局和响应式/可访问性要求见 `docs/frontend/2026-10-12-role-dashboard-handoff.md`。
