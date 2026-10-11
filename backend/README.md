# 后端基础工程

已实现后端基础设施以及登录/首次改密、数据库Bearer会话、方向范围授权、人员和负责人管理、学号更正/密码重置与审计。**日报周报、AI评分、积分、排行榜和自动移出尚未实现**。学生不能自行注册，只能由方向负责人或学院管理员添加。

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

正式JAR开发文档只显示已注册的业务API，描述/DTO依据已审阅的契约；实际HTTP响应另有契约断言。报告和月评等未实现路径不会显示为可用。测试探针不进入正式JAR，不提供自注册接口。健康原生JSON，不公开数据库细节或其他Actuator端点；数据库已连通但尚未迁移仍为DOWN/503。

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

当前不是完整业务系统。上线前仍需补齐报告/积分/AI、移出复核、账号私密交付、隐私保护、限流部署策略与备份恢复，不能因为已有认证就声称全业务完成。

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

学生和负责人使用学号登录；学生不能自注册。当前尚未实现日报、周报、AI 评分、积分、排行榜、月度评定及自动移出。

会话使用32字节随机不透明token，库内只存SHA-256摘要；密码采用Spring Security PBKDF2-HMAC-SHA256、16字节盐、600000迭代、256位输出。首次/重置后只发300秒受限access，不能refresh或调用业务，改密后重新登录。普通access900秒，refresh家族绝对7天，刷新轮换且旧token重放撤销家族；前端以响应expiresIn为准。改密/重置/学号更正/授权或状态变化立即撤销相关会话。

登录/刷新/改密均为单进程有界固定窗口限流（60秒10次）；429含Retry-After，不信任未配置代理的X-Forwarded-For。当前无Cookie认证、默认管理员或JSESSIONID，跨域默认不开放。多实例需要另行设计全局限流；共享初始密码也不能替代身份交付核验。默认TLS由部署入口负责。

用户指定的 MySQL 8.0 已于 2026 年 4 月进入 EOL 阶段，继续按已确认的版本完成开发验证，但上线前必须重新评估支持周期、安全修复和升级方案；参考已确认的技术设计及 MySQL 官方生命周期公告。默认及 `prod` 文档关闭不是认证方案，公网部署仍需要认证、TLS、受支持的数据库版本、最小权限和密钥管理。
