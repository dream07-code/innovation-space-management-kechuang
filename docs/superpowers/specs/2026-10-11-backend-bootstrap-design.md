# 后端基础工程初始化技术设计

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-11 |
| 文档版本 | v1.0 |
| 审阅状态 | 待用户审阅；本文件不代表已获准实施 |
| 开发分支 | backend |
| 业务依据 | [需求文档 v0.2](../../requirements.md) |
| 当前范围 | 单个 Maven 模块的后端基础工程，不实现完整业务功能 |

## 1. 已确认的目标

为学院科创空间管理系统建立可构建、可启动、可测试的后端基础工程，后续在此基础上实现人员、报告、积分和成员资格管理。

用户已确认 Java 21、Spring Boot、MyBatis-Plus、MySQL 8.0、Maven，以及单模块、先搭基础工程的范围。代码放在仓库的 `backend/` 目录，不影响根目录的需求文档，也不修改 `main` 分支。

成功标准是 Java 21 下构建通过、应用可以连接独立 MySQL 8.0 实例、健康检查能正确反映数据库状态，以及公共异常处理和 MyBatis-Plus 分页配置得到实际测试。生成目录和编译成功本身不等于全部验收通过。

## 2. 范围与明确不做的事项

### 2.1 本次交付

- 单模块 Maven 工程、Java 21 启动类和可运行 JAR。
- 固定版本的依赖配置、Maven Wrapper 和基础运行配置。
- 公共响应类型、参数校验、统一异常处理。
- MyBatis-Plus 配置、MySQL 连接池和分页插件。
- Spring Boot Actuator 健康检查。
- 单元测试、Web 层测试和真实 MySQL 8.0 集成测试。
- 环境变量示例、构建与启动说明、测试条件说明。
- 仅在实际需要时新增业务包；不得用空类和空接口假装完成业务模块。

### 2.2 本次不交付

不实现登录认证、学生账号创建接口、人员 CRUD、日报周报提交、AI 评分、积分流水、排行榜、月度评定或自动移出。也不引入 Redis、消息队列、微服务、服务注册中心、AI SDK 或前端工程。

不创建生产业务表、默认管理员或测试学生，不执行现有数据库迁移，不假定尚未确认的淘汰规则。不为了测试新增公开的演示 CRUD 接口。

学生只能由学院管理员或所属方向负责人添加，不能自行注册。本次没有注册接口；未来接入任何登录方式都不得自动创建未经管理人员添加的学生。

## 3. 架构与工程组织

采用单进程、单 Maven 模块的分层单体应用，暂不采用 Maven 多模块或微服务。业务域通过包隔离，不预先增加跨模块通信和共享基础类体系。

工程标识固定为：

| 标识 | 取值 |
| --- | --- |
| Maven groupId | io.github.dream07code |
| Maven artifactId | innovation-space-backend |
| 初始工程版本 | 0.1.0-SNAPSHOT |
| 根包名 | io.github.dream07code.innovationspace |
| 启动类 | InnovationSpaceApplication |
| 默认端口 | 8080，可通过 SERVER_PORT 覆盖 |
| 默认监听地址 | 127.0.0.1，可通过 SERVER_ADDRESS 显式覆盖 |

工程版本的 SNAPSHOT 仅表示本项目仍在开发，第三方依赖不得使用 SNAPSHOT、里程碑版本或版本范围。

预期目录职责如下，业务域子包在有实际代码时再建立：

```text
backend/
  pom.xml
  mvnw
  mvnw.cmd
  .mvn/wrapper/maven-wrapper.properties
  .env.example
  README.md
  src/main/java/io/github/dream07code/innovationspace/
    InnovationSpaceApplication.java
    common/api/
    common/exception/
    config/
  src/main/resources/
    application.yml
  src/test/java/io/github/dream07code/innovationspace/
    common/
    web/
    integration/
  src/test/resources/
```

后续业务按 `identity`、`member`、`report`、`point`、`assessment` 等包分别演进，不在本次生成无内容的业务结构。每个业务包自行拥有 Controller、Service、Mapper 和数据对象，不建立通用 BaseController 或 BaseService 来强制统一业务。

## 4. 依赖与版本决策

截至 2026-10-11，已只读核对发布元数据。选定以下稳定版本，完整依赖组合仍需在实施阶段编译和集成测试，不能以版本存在代替兼容性测试。

| 项目 | 决策 |
| --- | --- |
| Java | 编译目标及验收运行环境为 21，使用 maven.compiler.release=21。 |
| Spring Boot | 4.1.1，使用对应版本的 spring-boot-starter-parent。 |
| Maven | Wrapper 固定到 3.9.16，与已安装版本一致；常规构建使用 Wrapper。 |
| Web | Spring Boot 4 对应的 spring-boot-starter-webmvc。 |
| 参数校验 | spring-boot-starter-validation，由 Boot 管理版本。 |
| 健康检查 | spring-boot-starter-actuator，由 Boot 管理版本。 |
| MyBatis-Plus | mybatis-plus-spring-boot4-starter:3.5.17。 |
| 分页解析依赖 | mybatis-plus-jsqlparser:3.5.17，与 MyBatis-Plus 保持一致。 |
| MySQL JDBC | com.mysql:mysql-connector-j，runtime 范围，版本由 Boot BOM 管理。 |
| Web 与通用测试 | spring-boot-starter-webmvc-test，test 范围，由 Boot 管理版本。 |
| 真实数据库测试 | org.testcontainers:testcontainers-mysql 和 testcontainers-junit-jupiter，test 范围，由 Boot BOM 管理版本。 |
| 数据库服务端 | MySQL 8.0.46，不替换成宿主机 9.x 数据库。 |

Boot 4.1.1 的 BOM 管理 Connector/J 9.7.0、Testcontainers 2.0.5 和 JUnit Jupiter 6.0.3。驱动版本与数据库服务端版本是不同概念；服务端验收必须查询 `SELECT VERSION()` 并确认属于 8.0。

不另外混用 Boot 3 的 MyBatis-Plus Starter、独立 MyBatis Starter、手工指定 Spring Framework/Jackson 版本或 H2 数据库。不为本次初始化增加 Lombok，简单 DTO 可直接使用 Java record。

真实数据库测试固定使用已核对存在的官方多架构镜像：

```text
mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b
```

## 5. 配置、数据库与时间

### 5.1 配置边界

主配置文件不存放真实密码、访问令牌、真实学生信息或与开发者机器绑定的绝对路径。

| 环境变量 | 规则 |
| --- | --- |
| SERVER_PORT | 可选，默认 8080。 |
| SERVER_ADDRESS | 可选，默认 127.0.0.1。 |
| DB_HOST | 可选，默认 127.0.0.1。 |
| DB_PORT | 可选，默认 3306。 |
| DB_NAME | 可选，默认 innovation_space。 |
| DB_USERNAME | 必须提供，不默认使用 root。 |
| DB_PASSWORD | 必须提供，无默认密码；不得打印到日志。 |

`.env.example` 只记录变量名及非敏感示例。Spring Boot 不会因文件名为 `.env` 就自动加载它，启动说明必须明确通过 shell 环境或 IDE 配置注入，不能把示例文件误认为已生效的配置。

采用 HikariCP 连接池，配置有界连接等待；缺少必要环境变量时启动失败并给出不含密钥的诊断。提供了凭据但数据库不可用时，健康检查不得返回 UP。

业务日期统一使用 Asia/Shanghai。提供可注入的业务 Clock，测试可以固定时间，不以服务器默认时区推断业务日期。数据库连接按 UTC 处理时间传输；本次不定义业务表时间字段，也不修改服务器全局时区。

### 5.2 不触碰既有数据

应用启动不自动执行建表、删表或初始化 SQL，显式关闭自动 SQL 初始化。没有 JPA，因此不引入自动建表配置。

开发启动连接独立开发库，集成测试使用 Testcontainers 产生的一次性 MySQL 实例。集成测试不得接受指向生产或既有业务库的环境变量来替代容器连接。

测试所需的临时探针表、实体及 Mapper 仅存在于测试源码和测试资源中，容器关闭即清除，不进入生产包扫描范围。

## 6. 公共 API 与异常处理

未来业务 API 使用 `/api/v1` 前缀，本次不暴露该前缀下的生产业务接口。

定义小型 `ApiResponse<T>`，约定字段为 `code`、`message`、`data`。未来普通业务成功使用 `code=OK`；本次仅提供可测试的结构和异常映射，不增加自动包装全部响应的 ResponseBodyAdvice。

统一异常处理覆盖 JSON 解析失败、参数绑定及校验失败、路径不存在、HTTP 方法不匹配和未预期异常。

| 场景 | HTTP 状态 | code |
| --- | --- | --- |
| 请求体格式、绑定或参数校验错误 | 400 | INVALID_REQUEST |
| 路径不存在 | 404 | NOT_FOUND |
| 不支持的 HTTP 方法 | 405 | METHOD_NOT_ALLOWED |
| 未预期服务端异常 | 500 | INTERNAL_ERROR |

禁止把所有失败都转换为 HTTP 200。校验错误可返回字段名和安全提示，但不得回显密码字段的原始输入；未知异常只返回通用提示，堆栈留在服务端日志。

保留 `/error` 的内部错误处理职责，它不是业务 API。Actuator 的返回值保持原生格式，不套入 ApiResponse，不改变健康检查的 HTTP 状态。

错误映射测试使用仅在测试上下文注册的 Controller；不得把故意抛异常或绕过权限的演示接口部署出去。

## 7. MyBatis-Plus 与健康检查

### 7.1 MyBatis-Plus

注册 MybatisPlusInterceptor，包含针对 MYSQL 的 PaginationInnerInterceptor。单页最大条数为 200；请求超过上限时按插件配置限制到上限，不通过分页生成不受限的批量读取。

本次不在生产代码创建实体或 Mapper。集成测试加载测试专用 BaseMapper、创建隔离探针表，并实际验证插入、按 ID 查询及分页总数、页大小和 SQL 执行。

分页必须来自实际 MySQL 8.0 查询，不能只断言配置 Bean 存在，也不能用 H2 的行为替代 MySQL。

### 7.2 健康检查

仅暴露 `/actuator/health`，其他 Actuator 管理端点不公开，健康信息不显示组件详情、数据库地址或凭据。

健康检查采用 Actuator 原生聚合结果，包含数据库连接状态。数据库连接正常时为 HTTP 200 和 UP；数据库不可用时为 HTTP 503 和 DOWN，而不是始终返回固定字符串。

默认仅监听本机。当前没有认证和业务接口，不能将基础工程视为可公开部署的完整管理系统；新增业务接口前必须单独实现身份认证、角色权限及方向隔离。

## 8. 主要数据流

未来业务请求的数据流为：HTTP 请求 → Controller 参数校验 → Service 业务处理 → Mapper → MySQL。事务边界放在实际需要一致性的 Service 方法中，不在本次增加没有用途的通用事务封装。

本次实际可运行的链路只有：

1. 启动时加载外部配置，装配 Web、数据源、异常处理和分页组件。
2. 健康请求进入 Actuator，执行数据库健康检查，返回原生状态及相应 HTTP 状态。
3. 自动化测试注入固定 Clock、测试 Controller 或测试 Mapper，分别验证公共行为及真实数据库访问。

身份与方向权限、AI 任务、积分入账及定时任务的数据流属于后续业务设计，不在基础工程提前实现。

## 9. 测试与验收

Maven Surefire 运行 `*Test` 单元和 Web 测试。Maven Failsafe 运行 `*IT` 真实数据库集成测试，并在 verify 阶段判定结果。

`./mvnw test` 不要求 Docker，也不要求开发数据库；Web 测试不得为了避免连接失败而改写生产配置。测试按切片或测试上下文隔离数据库依赖。

`./mvnw verify` 必须运行真实 MySQL 8.0 集成测试，Docker 不可用时明确失败，不使用 disabledWithoutDocker 或隐式跳过来声称验证成功。依赖下载或镜像拉取失败也要分别报告，不能算作代码已通过完整验证。

| 编号 | 验收场景 | 通过条件 |
| --- | --- | --- |
| B-01 | 构建与打包 | Java 21 下通过编译、单元测试和打包，输出可运行 JAR。 |
| B-02 | Wrapper | Wrapper 固定 Maven 3.9.16，脚本可执行，Windows 脚本存在。 |
| B-03 | 参数错误 | 测试上下文中的无效 JSON 和无效参数返回 400 与安全错误结构。 |
| B-04 | HTTP 语义 | 未知路径为 404，不支持的方法为 405，未预期异常为 500；不泄露堆栈。 |
| B-05 | 数据源与数据库版本 | 容器内 SELECT VERSION() 为 8.0，数据源可执行 SELECT 1。 |
| B-06 | Mapper | 测试专用 Mapper 可在隔离表中插入和查询，生产包不包含探针接口或实体。 |
| B-07 | 分页 | 实际查询返回正确页数据和总数，单页上限 200 生效。 |
| B-08 | 健康检查正常 | 真实数据库正常时，健康端点为 200 和 UP，不套入公共响应结构。 |
| B-09 | 健康检查异常 | 数据库连接不可用时，健康端点为 503 和 DOWN，不显示连接详情。 |
| B-10 | 注册与接口边界 | 无学生自注册或业务 CRUD 入口，其他 Actuator 管理端点不公开。 |
| B-11 | 密钥与数据保护 | 仓库仅包含示例配置，不含真实凭据、学生数据或数据库备份；应用不自动执行 DDL。 |
| B-12 | 时间 | 业务 Clock 使用 Asia/Shanghai，固定时间测试不依赖宿主机默认时区。 |
| B-13 | 验证透明性 | 单元测试与集成测试分别报告，完整 verify 未运行或失败时不宣称全部通过。 |

数据库连接不可用的健康测试采用独立测试上下文或受控故障实例，不能停止或篡改开发者正在使用的数据库服务。测试清理仅作用于自己创建的一次性实例。

## 10. 已检查的本机环境与实施条件

2026-10-11 的只读检查结果：

| 项目 | 实际观察 |
| --- | --- |
| Java | OpenJDK 21.0.12.1，命令可用。 |
| Maven | 3.9.16，使用 Java 21，命令可用。 |
| Docker CLI | 29.9.0，命令可用。 |
| Docker 服务 | 沙箱外检查未找到 /var/run/docker.sock，尚不能确认可运行容器。 |
| Docker Compose | docker compose 命令未提供；本次不以 Compose 为必要条件。 |
| MySQL 客户端 | 9.7.1，不代表已经安装或运行 8.0 服务端。 |
| 本机 MySQL 服务 | 默认 /run/mysqld/mysqld.sock 未找到，默认连接未成功。 |

以上是检查时的状态，不代表用户没有安装工具。实施前需要复查 Docker 服务和访问权限；随后使用固定的 MySQL 8.0 镜像进行隔离验证。开发启动说明可直接使用 Docker CLI，不要求额外安装 Compose。

依赖下载、镜像拉取、访问 Docker socket 或启动系统服务可能需要用户批准。不得为绕过权限把私有凭据写入仓库，也不得自动扩大系统目录写权限或更改用户组。

### 10.1 MySQL 8.0 生命周期风险

MySQL 官方发布记录指出，8.0 在 2026 年 4 月随 8.0.46 达到生命周期结束。本次仍按用户明确指定的 8.0 开发和验收，不擅自改用宿主机 9.7.1。

这不是对 8.0 长期生产使用的推荐。正式上线前须单独评估受支持的服务端版本、维护安排和升级方案；该决策不阻塞本次隔离环境中的基础工程验证。

## 11. 审阅与后续阶段

本文件只定义基础工程技术设计，不是实施计划，也没有授权跳过 Superpowers 的审阅步骤。

用户审阅此设计并确认后，下一步使用 writing-plans 生成细化实施计划。实施计划获确认且执行方式明确后，再建立工程、采用测试驱动开发、执行构建和数据库验证，并提交到 backend 分支。

本次设计阶段不会生成 pom.xml、Java 源码、下载项目依赖、启动数据库或创建业务表。

## 12. 核对依据

版本元数据及官方说明均于 2026-10-11 只读核对，来源为发布方或其正式发布仓库：

- [Spring Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)。
- [Spring Boot Parent 发布元数据](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-starter-parent/maven-metadata.xml)。
- [Spring Boot 4.1.1 BOM](https://repo.maven.apache.org/maven2/org/springframework/boot/spring-boot-dependencies/4.1.1/spring-boot-dependencies-4.1.1.pom)。
- [MyBatis-Plus Boot 4 Starter 发布元数据](https://repo.maven.apache.org/maven2/com/baomidou/mybatis-plus-spring-boot4-starter/maven-metadata.xml)。
- [MyBatis-Plus 分页依赖发布元数据](https://repo.maven.apache.org/maven2/com/baomidou/mybatis-plus-jsqlparser/maven-metadata.xml)。
- [MySQL 8.0 官方发布记录与生命周期提示](https://dev.mysql.com/doc/relnotes/mysql/8.0/en/)。
- [MySQL Connector/J 官方版本兼容说明](https://dev.mysql.com/doc/connector-j/en/connector-j-versions.html)。
- [MySQL 8.0.46 官方镜像标签及摘要](https://hub.docker.com/v2/repositories/library/mysql/tags/8.0.46)。
