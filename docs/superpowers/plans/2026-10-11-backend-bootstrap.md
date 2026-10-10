# Backend Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Execution method must be confirmed by the user before implementation.

**Goal:** 初始化可构建、可启动、可测试的后端基础工程，并提供开发环境 Knife4j 接口文档，不实现业务模块。

**Architecture:** 单个 Maven 模块位于 backend/，按业务包演进，公共响应与基础配置各自承担单一职责。真实数据库验证使用独立 Testcontainers MySQL 实例；仅测试上下文加载探针 Controller 和 Mapper。任务按顺序执行，不并行修改 POM 或应用配置。

**Tech Stack:** Java 21、Spring Boot 4.1.1、MyBatis-Plus 3.5.17、MySQL 8.0.46、Maven 3.9.16、springdoc 3.1.1、Knife4j 独立 UI 4.5.0。

**Spec:** [已确认的后端基础工程技术设计 v1.1](../specs/2026-10-11-backend-bootstrap-design.md)，业务依据为[需求文档 v0.3](../../requirements.md)。

**Plan Status:** v1.0，待用户审阅并确认执行方式。2026-10-11 用户的“继续”确认了技术设计并授权编写本计划，不代表本计划已获准执行。

## Global Constraints

- 工作分支为 backend，main 不修改、不强推；执行前复查工作区，保留用户已有改动。
- 所有下述文件路径相对于仓库根目录；当前根目录为 /home/dream/Projects/innovation-space-management。Maven 命令在 backend/ 内运行，Git 命令在仓库根目录运行。
- groupId=io.github.dream07code，artifactId=innovation-space-backend，工程版本=0.1.0-SNAPSHOT，根包=io.github.dream07code.innovationspace。
- 启动类=InnovationSpaceApplication；编译目标和验收环境为 Java 21；Wrapper 固定 Maven 3.9.16。
- Spring Boot Parent=4.1.1；MyBatis-Plus Boot 4 Starter 和 mybatis-plus-jsqlparser 均为 3.5.17。
- springdoc-openapi-starter-webmvc-ui=3.1.1，knife4j-openapi3-ui=4.5.0；不引入 Knife4j Boot 3 Starter、Springfox 或 Swagger 2。
- JDBC 驱动、JUnit、Testcontainers 和 Boot Starter 版本沿用 Boot 管理；第三方依赖不使用 SNAPSHOT、里程碑或版本范围。
- 测试镜像固定为 mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b；不以宿主机 9.x 或 H2 代替。
- 默认监听 127.0.0.1:8080，可通过 SERVER_ADDRESS、SERVER_PORT 覆盖；业务 Clock 使用 Asia/Shanghai，JDBC 会话使用 UTC。
- DB_USERNAME、DB_PASSWORD 无默认值，缺失或空白时启动失败；日志、示例和提交中不得有真实密码、令牌或学生数据。
- 应用不自动执行 DDL，不创建业务表或默认管理员，不触碰现有数据库。
- 学生只能由学院管理员或所属方向负责人添加，本次无注册、登录、业务 CRUD、AI、积分或淘汰接口。
- 运行状态仅公开 /actuator/health，保持原生 JSON 和正确 HTTP 状态，不泄露详情。
- Knife4j 主入口=/doc.html，OpenAPI 分组=innovation-space，规范=OpenAPI 3.0；仅 dev 启用，默认及 prod 关闭页面、JSON 和文档资源。
- ./mvnw test 不依赖 Docker 或开发数据库；./mvnw verify 必须执行真实 MySQL 集成测试，环境缺失时不能隐式跳过。
- 手工源码和文档改动使用 apply_patch；官方 Wrapper 生成和构建产物生成不手工仿写。
- 未经用户确认，不更换已选框架版本、不扩大业务范围、不修改系统用户组或既有数据库服务。

## Review Focus

- HTTP 边界：未知路径、错误方法和错误 Content-Type 应保留 404、405、415 及必要响应头，而不是变成 200 或 500；归属 Task 2。
- 密钥边界：无效字段值、异常消息和配置对象字符串不能把密码带入错误响应或日志；归属 Task 2 与 Task 3。
- 测试隔离：宿主机数据库变量、容器结束及 Spring 上下文缓存不能使测试访问既有库或复用已关闭实例；归属 Task 3 与 Task 4。
- 文档暴露：关闭 JSON 后静态 HTML 仍可访问、UI 资源失败或标准 JSON 被统一响应包装，都应被发现；归属 Task 4。
- 发布边界：测试探针、测试启动器和默认管理员不能进入生产运行链路；打包后的服务也应满足健康与文档边界；归属 Task 5。

---

## 文件与职责

以下目录在有实际文件时建立，不创建占位业务包。测试支持包放在 io.github.dream07code.testsupport，位于生产根包之外，由测试显式导入。

| 文件或文件组 | 职责 | 所属任务 |
| --- | --- | --- |
| backend/pom.xml、mvnw、mvnw.cmd、.mvn/wrapper/maven-wrapper.properties | 固定构建、依赖及测试阶段。 | Task 1；Task 3 增加 Failsafe 配置 |
| backend/src/main/java/io/github/dream07code/innovationspace/InnovationSpaceApplication.java | 唯一生产启动类。 | Task 1 |
| backend/src/main/java/io/github/dream07code/innovationspace/config/TimeConfiguration.java | 可注入的业务 Clock。 | Task 1 |
| backend/src/main/java/io/github/dream07code/innovationspace/common/api/ApiResponse.java、ErrorCode.java | 公共响应记录与安全错误定义。 | Task 2 |
| backend/src/main/java/io/github/dream07code/innovationspace/common/exception/GlobalExceptionHandler.java | HTTP 异常映射及安全日志。 | Task 2 |
| backend/src/main/java/io/github/dream07code/innovationspace/config/DatabaseCredentialsProperties.java、DatabaseConfiguration.java | 凭据绑定、非空校验及安全诊断。 | Task 3 |
| backend/src/main/java/io/github/dream07code/innovationspace/config/MyBatisPlusConfiguration.java | MySQL 分页插件，单页最多 200。 | Task 3 |
| backend/src/main/java/io/github/dream07code/innovationspace/config/OpenApiConfiguration.java | dev 环境 OpenAPI 信息及分组。 | Task 4 |
| backend/src/main/resources/application.yml | 共用配置、数据源及默认关闭文档。 | Task 1 创建；Task 2–4 按职责修改 |
| backend/src/main/resources/application-dev.yml、application-prod.yml | 文档开关与静态资源边界。 | Task 4 |
| backend/src/test/java/io/github/dream07code/innovationspace/ | 纯 Java、Web 和集成测试。 | Task 1–5 |
| backend/src/test/java/io/github/dream07code/testsupport/WebProbeConfiguration.java | 显式导入的测试 Controller 与 DTO。 | Task 2；Task 4 补充文档注解 |
| backend/src/test/java/io/github/dream07code/testsupport/MysqlTestSupport.java、PersistenceProbeConfiguration.java | 容器工厂、动态数据源及探针 Mapper。 | Task 3 |
| backend/src/test/java/io/github/dream07code/testsupport/BrowserSmokeApplication.java | 仅测试类路径下的浏览器联调启动器。 | Task 5 |
| backend/.env.example、backend/README.md、README.md、.gitignore | 安全示例、运行说明、状态及产物排除。 | Task 3、Task 5 |

## Task 1: 可构建工程与业务 Clock

**Files:**
- Create: backend/pom.xml、backend/mvnw、backend/mvnw.cmd、backend/.mvn/wrapper/maven-wrapper.properties。
- Create: backend/src/main/java/io/github/dream07code/innovationspace/InnovationSpaceApplication.java。
- Create: backend/src/main/java/io/github/dream07code/innovationspace/config/TimeConfiguration.java。
- Create: backend/src/main/resources/application.yml。
- Test: backend/src/test/java/io/github/dream07code/innovationspace/ApplicationMetadataTest.java。
- Test: backend/src/test/java/io/github/dream07code/innovationspace/config/TimeConfigurationTest.java。
- Modify: .gitignore，仅补充 target/ 与本次确需的本地验证产物排除规则。

**Interfaces:**
- Consumes: Global Constraints 中的工程标识、版本与业务时区。
- Produces: public static void InnovationSpaceApplication.main(String[] args)。
- Produces: public Clock TimeConfiguration.businessClock()，注册为 Clock Bean。
- Produces: ./mvnw test 和 ./mvnw package 的基本构建入口；本任务不宣称完整应用启动或数据库已通过验收。

- [ ] **Step 1: 建立最小测试构建环境。** POM 使用设计中的 Parent 与依赖，不增加其他业务库，设置 release=21；声明 Boot 管理的 spring-boot-maven-plugin 并沿用其 repackage 执行，Surefire 匹配普通 *Test。仅准备构建入口及产物排除规则，暂不写生产启动类及 Clock 实现。
- [ ] **Step 2: 写失败测试。** 写 TimeConfigurationTest 和 ApplicationMetadataTest。Clock 用例名为 businessClockUsesShanghai 与 fixedClockCrossesShanghaiMidnight，核心断言：

```java
assertEquals(ZoneId.of("Asia/Shanghai"), new TimeConfiguration().businessClock().getZone());
Clock fixed = Clock.fixed(Instant.parse("2026-10-10T16:30:00Z"), ZoneId.of("Asia/Shanghai"));
assertEquals(LocalDate.of(2026, 10, 11), LocalDate.now(fixed));
```

ApplicationMetadataTest 检查启动类的 SpringBootApplication 注解、包名，以及编译类文件 major version 为 65；二进制头使用 DataInputStream 读取，不用文本匹配。

- [ ] **Step 3: 运行红灯。** 在 backend/ 运行 mvn -Dtest=TimeConfigurationTest,ApplicationMetadataTest test。预期因为生产类尚不存在编译失败；依赖下载失败不能算作这次红灯证明。
- [ ] **Step 4: 实现启动类与 Clock。** main 仅委托 SpringApplication.run；TimeConfiguration 返回 Clock.system(ZoneId.of("Asia/Shanghai"))，不依赖系统默认时区。主配置设置应用名和默认监听地址/端口，不主动激活 dev。
- [ ] **Step 5: 生成官方 Wrapper。** 运行 mvn -N org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper -Dmaven=3.9.16 -Dtype=only-script；确认只有脚本及 properties，固定分发版本，Unix 脚本可执行，不手工编造脚本。
- [ ] **Step 6: 验证绿灯与可打包。** 运行 ./mvnw -version、./mvnw -Dtest=TimeConfigurationTest,ApplicationMetadataTest test、./mvnw package。预期 Maven 3.9.16、Java 21、测试通过、生成 target/innovation-space-backend-0.1.0-SNAPSHOT.jar；此时未执行真实数据库测试。
- [ ] **Step 7: 提交本任务。** 显式 git add 本任务列出的文件，确认 git diff --cached --check 无输出，再提交：feat: bootstrap Java 21 Maven backend and business clock。

## Task 2: 公共响应、校验与 HTTP 异常语义

**Files:**
- Create: backend/src/main/java/io/github/dream07code/innovationspace/common/api/ApiResponse.java、ErrorCode.java。
- Create: backend/src/main/java/io/github/dream07code/innovationspace/common/exception/GlobalExceptionHandler.java。
- Create: backend/src/test/java/io/github/dream07code/testsupport/WebProbeConfiguration.java。
- Test: backend/src/test/java/io/github/dream07code/innovationspace/common/ApiResponseTest.java。
- Test: backend/src/test/java/io/github/dream07code/innovationspace/web/GlobalExceptionHandlerTest.java。
- Modify: backend/src/main/resources/application.yml，仅增加默认错误信息隐藏设置。

**Interfaces:**
- Consumes: Task 1 的 Boot MVC/Validation/Test 依赖。
- Produces: public record ApiResponse<T>(String code, String message, T data)。
- Produces: public static <T> ApiResponse<T> ApiResponse.success(T data)，code=OK、message=Success。
- Produces: public static ApiResponse<Void> ApiResponse.failure(ErrorCode error)，data=null。
- Produces: ErrorCode 枚举 INVALID_REQUEST、NOT_FOUND、METHOD_NOT_ALLOWED、INTERNAL_ERROR，public String message() 分别为 Invalid request、Resource not found、Method not allowed、Internal server error。
- Produces: GlobalExceptionHandler extends ResponseEntityExceptionHandler；覆盖 protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request)，并提供 public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex)。
- Produces: WebProbeConfiguration，测试通过显式 Import 注册嵌套 Controller。GET /api/v1/test-probe/ping 返回 ApiResponse.success("pong")；POST /api/v1/test-probe/validate 校验 ProbeRequest；GET /api/v1/test-probe/failure 抛出测试异常；GET /api/v1/test-probe/query 校验 count>=1。

- [ ] **Step 1: 写失败测试及测试探针。** ProbeRequest 是 record，包含 NotBlank name 和 Size(min=8) password；不得返回 password。用 MVC 测试切片显式导入 WebProbeConfiguration 和待实现的 Advice，不装配数据库。ApiResponseTest 断言成功和失败记录字段；GlobalExceptionHandlerTest 使用下列断言类别，均有独立用例名：

```java
assertEquals("OK", ApiResponse.success("pong").code());
assertNull(ApiResponse.failure(ErrorCode.INVALID_REQUEST).data());
mockMvc.perform(post("/api/v1/test-probe/validate")
        .contentType(APPLICATION_JSON).content("{broken"))
    .andExpect(status().isBadRequest())
    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
mockMvc.perform(post("/api/v1/test-probe/ping"))
    .andExpect(status().isMethodNotAllowed())
    .andExpect(header().string("Allow", containsString("GET")));
mockMvc.perform(get("/does-not-exist"))
    .andExpect(status().isNotFound());
mockMvc.perform(post("/api/v1/test-probe/validate")
        .contentType(TEXT_PLAIN).content("text"))
    .andExpect(status().isUnsupportedMediaType());
```

补充 blankNameReturns400、shortPasswordIsNotEchoed、invalidQueryReturns400、unexpectedFailureIsSanitized；假敏感值统一为 secret7。错误响应不得含 secret7、异常类名或 trace，捕获的应用日志也不得记录该值。

- [ ] **Step 2: 运行红灯。** ./mvnw -Dtest=ApiResponseTest,GlobalExceptionHandlerTest test；预期缺少响应/Advice 或错误 JSON、状态语义不符合断言。
- [ ] **Step 3: 实现公共记录与 Advice。** 对框架异常保留传入状态和 headers，404/405 分别使用对应错误码，其他请求侧 4xx 用 INVALID_REQUEST，5xx 用 INTERNAL_ERROR；已提交响应保持框架处理约定，不重复写入。未预期异常返回 500。日志保留类型与调用栈，不直接拼接请求体、rejectedValue 或可能含敏感输入的异常消息；不添加 ResponseBodyAdvice。
- [ ] **Step 4: 关闭默认错误泄露。** 在实际 Boot 4 的 spring.web.error 配置中设 include-message=never、include-binding-errors=never、include-stacktrace=never、include-exception=false；覆盖 /error 回退行为，不使用旧版 server.error 属性来假装生效。
- [ ] **Step 5: 验证绿灯。** ./mvnw test，确认仍不需要 Docker 或数据库凭据；分别核对 400、404、405、415、500，405 的 Allow 和敏感输入日志断言。
- [ ] **Step 6: 提交本任务。** 显式暂存本任务文件并检查差异，提交：feat: add validated API responses and safe HTTP error handling。

## Task 3: 数据源、分页与真实数据库健康检查

**Files:**
- Create: backend/src/main/java/io/github/dream07code/innovationspace/config/DatabaseCredentialsProperties.java、DatabaseConfiguration.java、MyBatisPlusConfiguration.java。
- Create: backend/.env.example。
- Create: backend/src/test/java/io/github/dream07code/testsupport/MysqlTestSupport.java、PersistenceProbeConfiguration.java。
- Test: backend/src/test/java/io/github/dream07code/innovationspace/config/DatabaseConfigurationTest.java、MyBatisPlusConfigurationTest.java。
- Test: backend/src/test/java/io/github/dream07code/innovationspace/integration/MysqlPersistenceIT.java、HealthEndpointIT.java、UnavailableDatabaseHealthIT.java。
- Modify: backend/pom.xml、backend/src/main/resources/application.yml。

**Interfaces:**
- Consumes: Task 1 的启动类与 Clock，Task 2 的异常边界。
- Produces: public record DatabaseCredentialsProperties(String username, String password)，以 spring.datasource 前缀绑定并验证 NotBlank；toString() 不输出任何凭据值。
- Produces: DatabaseConfiguration 使用 EnableConfigurationProperties 注册凭据校验，不手工替换 Boot 的 DataSource 工厂。
- Produces: public MybatisPlusInterceptor MyBatisPlusConfiguration.mybatisPlusInterceptor()，MYSQL 分页、maxLimit=200L。
- Produces: MysqlTestSupport.MYSQL_IMAGE 为固定镜像常量；public static MySQLContainer newMysqlContainer()；public static void registerDataSource(DynamicPropertyRegistry registry, MySQLContainer container)。使用 Testcontainers 2 的 org.testcontainers.mysql.MySQLContainer，不带旧版本泛型。
- Produces: PersistenceProbeConfiguration，仅测试显式导入；含 bootstrap_probe 表对应 ProbeEntity 和 ProbeMapper extends BaseMapper<ProbeEntity>。ID 使用 INPUT，字段为 Long id、String name；实体提供无参构造、public Long getId()、public void setId(Long id)、public String getName()、public void setName(String name)。测试自己建表并清理。

- [ ] **Step 1: 写失败的配置单元测试。** DatabaseConfigurationTest 分别覆盖缺失、空白 username/password；示例有效密码只用于测试且不出现在配置对象字符串或失败日志中。MyBatisPlusConfigurationTest 检查 MYSQL、200L；生产 YAML 使用 YamlPropertySourceLoader 等结构化 API 检查无密码默认值及 SQL 初始化关闭，不做随意字符串截取。
- [ ] **Step 2: 运行红灯。** ./mvnw -Dtest=DatabaseConfigurationTest,MyBatisPlusConfigurationTest test，预期缺少配置类或限制不符合约定。
- [ ] **Step 3: 写真实 MySQL 集成测试和 fixture。** 每个正向测试类有自己的 Container 字段，并在该类的 DynamicPropertySource 中注册容器 URL、用户名与随机生成的测试密码。不得共享跨类后被关闭的静态容器，不启用 reusable containers 或 Docker 缺失自动跳过。MysqlPersistenceIT 包含连接、Mapper、分页和配置污染用例，关键断言为：

```java
assertThat(jdbcTemplate.queryForObject("SELECT VERSION()", String.class)).startsWith("8.0.46");
assertEquals(1, jdbcTemplate.queryForObject("SELECT 1", Integer.class));
assertEquals("probe-1", probeMapper.selectById(1L).getName());
assertEquals(205L, probeMapper.selectPage(new Page<>(1, 500), null).getTotal());
assertEquals(200, probeMapper.selectPage(new Page<>(1, 500), null).getRecords().size());
```

实际准备 205 条隔离记录，再验证第二页为 5 条及不存在的 ID；需要断言记录顺序时显式按 ID 排序。向测试属性源设置错误的开发库 URL，确认高优先级动态配置仍仅连接本类容器。无容器的故障测试使用本机受控拒绝连接地址与独立上下文，不停止真实数据库。健康正向和反向断言分别为 200/UP 与 503/DOWN，JSON 不含 code、components、details、数据库地址或凭据；HealthEndpointIT 还须确认 /actuator/env、/actuator/info、/actuator/beans、/actuator/configprops 均为 404。

- [ ] **Step 4: 绑定 Failsafe 并运行红灯。** POM 中声明 Boot 管理的 Failsafe，绑定 integration-test 和 verify，匹配 **/*IT.java，failIfNoTests=true；Surefire 只运行普通 *Test。运行 ./mvnw -Dit.test=MysqlPersistenceIT,HealthEndpointIT,UnavailableDatabaseHealthIT verify。预期因尚未实现的配置类或配置行为失败；Docker/拉取失败单独记录，不算行为红灯，也不故意破坏已完成实现制造失败。
- [ ] **Step 5: 实现配置及示例。** DB_HOST=127.0.0.1、DB_PORT=3306、DB_NAME=innovation_space，DB_USERNAME/DB_PASSWORD 不提供默认值；JDBC 加入 connectionTimeZone=UTC、forceConnectionTimeZoneToSession=true。配置 connection-timeout=3000ms、validation-timeout=1000ms、initialization-fail-timeout=-1、minimum-idle=0、maximum-pool-size=5，允许数据库不可用时返回健康 DOWN，但凭据校验仍必须失败。SQL 初始化 mode=never；健康详情和组件均 never，仅暴露 health，关闭额外 probes 路径。示例不包含真实凭据。
- [ ] **Step 6: 验证绿灯。** 校准动态连接和测试 Mapper 注册，不更换数据库目标、不把探针移到生产代码。运行 ./mvnw test 与上述定向 verify；报告实际测试数量、失败和跳过数，完整集成测试必须执行而不是跳过。
- [ ] **Step 7: 提交本任务。** 仅暂存本任务配置、测试和示例文件，检查无凭据或数据库产物，提交：feat: configure MySQL pagination and database health checks。

## Task 4: Knife4j 文档接入与环境隔离

**Files:**
- Create: backend/src/main/java/io/github/dream07code/innovationspace/config/OpenApiConfiguration.java。
- Create: backend/src/main/resources/application-dev.yml、application-prod.yml。
- Test: backend/src/test/java/io/github/dream07code/innovationspace/config/OpenApiConfigurationTest.java。
- Test: backend/src/test/java/io/github/dream07code/innovationspace/integration/DevApiDocumentationIT.java、ProdApiDocumentationIT.java、DefaultApiDocumentationIT.java。
- Modify: backend/src/main/resources/application.yml、backend/src/test/java/io/github/dream07code/testsupport/WebProbeConfiguration.java。

**Interfaces:**
- Consumes: Task 2 的 WebProbeConfiguration 与响应类型，Task 3 的 MysqlTestSupport；每个集成测试类持有独立容器。
- Produces: @Profile("dev") 的 OpenApiConfiguration，public OpenAPI innovationSpaceOpenApi()、public GroupedOpenApi innovationSpaceApi()。
- Produces: 文档 title=学院科创空间管理系统 API、version=v1，group=innovation-space，paths=/api/v1/**；GroupedOpenApi 只设 group 与路径，dev YAML 的 springdoc.packages-to-scan 固定生产根包，不在分组 Bean 中再次硬编码包过滤。生产组件扫描不包含 testsupport。
- Produces: dev 中 /doc.html、/v3/api-docs/swagger-config、/v3/api-docs/innovation-space，OpenAPI 3.0；默认及 prod 中这些入口和文档资源均不可访问。

- [ ] **Step 1: 写失败测试。** OpenApiConfigurationTest 检查 title、v1 和 group。DevApiDocumentationIT 显式导入测试探针，不更改生产扫描根包；检查 doc.html 的内容与引用资源、swagger-config 的分组 URL、分组 JSON 未被 ApiResponse 包装，以及测试请求 name 必填、password 仅写入、成功响应及 400/500 说明。核心 JSON 断言：

```java
assertThat(openApi.get("openapi").asText()).startsWith("3.0.");
assertEquals("学院科创空间管理系统 API", openApi.at("/info/title").asText());
assertEquals("v1", openApi.at("/info/version").asText());
assertTrue(openApi.get("paths").has("/api/v1/test-probe/ping"));
assertFalse(openApi.get("paths").has("/actuator/health"));
assertFalse(openApi.has("code"));
```

ProdApiDocumentationIT 和 DefaultApiDocumentationIT 不导入探针，参数化检查 /doc.html、/v3/api-docs、/v3/api-docs/swagger-config、/v3/api-docs/innovation-space、/swagger-ui.html、/swagger-ui/index.html 以及从 dev HTML 识别的实际 JS/CSS 资源 URL 为 404；健康仍为 200/UP。

- [ ] **Step 2: 运行红灯。** ./mvnw -Dtest=OpenApiConfigurationTest test 与 ./mvnw -Dit.test=DevApiDocumentationIT,ProdApiDocumentationIT,DefaultApiDocumentationIT verify。预期缺少配置、分组、JSON 或非 dev 静态页面仍暴露；若环境问题先修环境诊断，不算接入测试失败证明。
- [ ] **Step 3: 实现配置和文档契约。** 注册 OpenAPI 信息与 GroupedOpenApi，仅 dev 生效；配置输出 OpenAPI 3.0。默认和 prod 将 springdoc.api-docs.enabled、springdoc.swagger-ui.enabled、spring.web.resources.add-mappings 设为 false；dev 将这三项设为 true。使用指定独立 UI，不添加 EnableKnife4j 或来自旧 Starter 的增强配置，不伪造认证方案。
- [ ] **Step 4: 完成测试探针注解与资源验证。** 仅给 testsupport 中的 DTO 和 Controller 添加 Operation、Tag、Schema、响应说明。在 DevApiDocumentationIT 中覆盖 springdoc.packages-to-scan=io.github.dream07code.testsupport，不改变生产组件扫描；分组的路径过滤仍生效。生产配置仍仅筛选生产根包。HTML 资源链接通过 HTML 解析器或浏览器提取，不能用一个固定虚构 JS 路径替代实际资源检查。
- [ ] **Step 5: 验证绿灯。** 运行 ./mvnw test 和定向 verify，确认默认、dev、prod 三类独立上下文的断言都执行。用未导入探针的 dev 上下文确认 paths 为空也能输出有效分组文档；不为了填充文档新增生产接口。兼容失败时记录错误并停止本任务，不擅自改 Boot 版本或文档工具。
- [ ] **Step 6: 提交本任务。** 显式暂存文档配置与测试，检查 POM 不包含 Boot 3 Knife4j Starter，提交：feat: integrate Knife4j OpenAPI docs for development only。

## Task 5: 发布边界、浏览器联调与完整验收

**Files:**
- Create: backend/README.md。
- Create: backend/src/test/java/io/github/dream07code/testsupport/BrowserSmokeApplication.java。
- Test: backend/src/test/java/io/github/dream07code/innovationspace/integration/JarBoundaryIT.java。
- Modify: README.md，记录实际已完成范围，不声称实现业务系统。
- Update during execution: 本计划任务复选框，仅在对应任务及验证完成后勾选。

**Interfaces:**
- Consumes: Task 1–4 的 JAR、公共配置、WebProbeConfiguration 和 MysqlTestSupport。
- Produces: public static void BrowserSmokeApplication.main(String[] args)，只在测试类路径存在。启动自己的一次性数据库和导入探针的 dev 应用，监听随机本地端口；通过 target/browser-smoke.json 输出只含 baseUrl 的就绪信息，不输出数据库密码。
- Produces: target/verification/ 中的临时浏览器截图及验证输出，不提交截图、日志、数据库文件或运行配置。
- Produces: 可按说明从干净环境启动的正式 JAR，以及包含实际测试结果的交付说明。

- [ ] **Step 1: 写发布边界失败测试。** JarBoundaryIT 使用 JarFile 读取 target/innovation-space-backend-0.1.0-SNAPSHOT.jar，断言 BOOT-INF/classes/ 不含 testsupport、Probe、BrowserSmokeApplication、.env 或生产 SQL 初始化资源；检查实际启动类存在。运行 ./mvnw -Dit.test=JarBoundaryIT verify；若现有标准打包已满足边界，保留此回归测试，不故意引入泄漏制造红灯。
- [ ] **Step 2: 实现仅测试的浏览器启动器。** 使用已有容器工厂，通过高优先级 PropertySource 注入动态 JDBC 凭据，不把密码放入命令行或就绪 JSON。明确导入生产应用与 WebProbeConfiguration，设 dev、server.port=0 和仅本上下文的 springdoc.packages-to-scan=io.github.dream07code.testsupport；注册关闭钩子，任何失败都关闭自己创建的上下文和容器。
- [ ] **Step 3: 浏览器实际验证 Knife4j。** 先 test-compile，再使用 Boot 管理的 dependency:build-classpath 生成 target/test-classpath.txt；运行测试启动器，读取其 baseUrl。用可用的浏览器自动化能力，优先 Playwright，访问 /doc.html 并检查服务 title、innovation-space 分组及页面 JS/网络错误；实际从页面调试 GET ping，响应须为 HTTP 200、code=OK、data=pong。截图存到 target/verification/knife4j-dev.png；测试探针仍不得进入正式 JAR。浏览器不可用时报告此验收项未完成，不能以 HTML 200 代替。
- [ ] **Step 4: 验证正式 JAR 的默认和 dev 行为。** 在独立测试库上用 Java 21 启动正式 JAR，使用临时本地端口，确认 health 为 UP，生产包中的 dev 分组 paths 为空，测试探针路由为 404。默认/prod 时文档入口为 404。结束后停止自己创建的进程和容器，不停止用户数据库或其他服务。
- [ ] **Step 5: 写可执行的启动说明。** README 说明 Java 21、Maven Wrapper、Docker 服务前提、独立 MySQL 8.0 和非 root 用户，环境变量如何注入、默认/dev/prod 的差异、health 和 doc.html 地址、test 与 verify 的区别。提供 Docker CLI 路径，不要求 Compose；明确 .env 不自动加载、基础工程没有认证或业务 API、MySQL 8.0 生命周期风险及上线前检查。不在命令示例放入真实凭据。
- [ ] **Step 6: 完整验收。** ./mvnw clean verify，必须记录 Surefire/Failsafe 实际测试数量与失败、跳过情况；检查缺失凭据、数据库不可用、分页上限、文档三种环境和 JarBoundaryIT。运行 git diff --check，检查依赖树无 Boot 3 Starter、H2 或重复 MyBatis，并检查暂存内容无真实密钥。完整 verify 和浏览器验收均通过后才宣称初始化完成。
- [ ] **Step 7: 统一代码审查与修复。** 按 requesting-code-review 流程复查整个 backend 分支，重点检查五项 Review Focus；独立审查仅在执行方式或适用技能明确授权时使用代理。修复发现的问题并重新跑相关测试，不仅依赖审查者结论。
- [ ] **Step 8: 提交和推送。** 仅暂存已核对的代码、测试、示例和说明，提交：test: verify backend packaging and Knife4j browser workflow。推送 origin backend，并读回远程提交编号与本地比较；用户未要求时不合并 main。交付说明列出运行地址或启动方法、测试结果、未完成项及生命周期风险。

## 验收覆盖矩阵

| 设计验收项 | 计划责任与证据 |
| --- | --- |
| B-01 | Task 1 编译及打包，Task 5 正式 JAR 启动与 clean verify。 |
| B-02 | Task 1 官方 Wrapper、版本及脚本权限检查。 |
| B-03 | Task 2 JSON、字段、请求参数校验用例。 |
| B-04 | Task 2 404、405、500 与响应头；补充 415 回归。 |
| B-05 | Task 3 容器版本及 SELECT 1，不接受开发库替代。 |
| B-06 | Task 3 探针 Mapper 实际插入、查询；Task 5 包边界。 |
| B-07 | Task 3 205 条记录、真实分页总数和 200 上限。 |
| B-08 | Task 3 HealthEndpointIT 与正式 JAR 正向检查。 |
| B-09 | Task 3 UnavailableDatabaseHealthIT，受控失败，不动现有数据库。 |
| B-10 | Task 4–5 无自注册/业务路由，其他 Actuator 入口不公开。 |
| B-11 | Task 2–3 安全响应与凭据校验，Task 5 配置、JAR 和暂存扫描。 |
| B-12 | Task 1 上海时区及固定时间，Task 3 JDBC UTC。 |
| B-13 | Task 3 Failsafe 非隐式跳过，Task 5 测试报告及未完成项说明。 |
| B-14 | Task 4 dev 文档 JSON、分组、注解及资源检查。 |
| B-15 | Task 5 浏览器渲染、真实页面调试和截图。 |
| B-16 | Task 4 默认/prod JSON 和静态入口关闭，健康不受影响。 |

## 执行前检查与失败处理

- 用户确认本计划及执行方式后，按 using-git-worktrees 检查是否需要隔离工作树；无论工作目录如何选择，起点必须包含 backend 上已批准的设计和计划，不能误从旧 main 初始化。
- 复查 java -version、mvn -version、docker info 和实际仓库状态；Docker 不可用时可推进不依赖它的单元任务，但真实数据库及浏览器验收必须明确保持未完成。
- 网络/镜像失败与测试断言失败分别记录；保留完整失败证据，采用 systematic-debugging 排查，不随意换镜像或删测试绕过。
- 不默认启动、重置或清空现有 MySQL；不执行 docker system prune、volume prune 或其他全局清理。
- 本计划任务默认顺序执行。推荐 Native：由当前会话逐项实现，并在最后进行统一代码审查，因为 POM、配置和测试 fixture 的依赖较紧密。逐任务代理实现与审查是另一种执行方式，需要用户明确选择，当前尚未授权派发。
- 计划获审阅后才加载 executing-plans 或 subagent-driven-development 进入实现；本次计划阶段不安装项目依赖、不启动服务、不生成源码。

## 官方实现参考

- [Maven Wrapper Plugin 3.3.4：only-script 与 Maven 版本参数](https://maven.apache.org/tools/wrapper/maven-wrapper-plugin/wrapper-mojo.html)。
- [Spring Framework 7 ResponseEntityExceptionHandler：状态及响应头契约](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/ResponseEntityExceptionHandler.html)。
- [Spring Boot 4.1.1 配置属性：spring.web.error 与 spring.web.resources](https://docs.spring.io/spring-boot/appendix/application-properties/index.html)。
- [Spring Boot 4.1.1 测试切片与 TestConfiguration](https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html)。
- [Testcontainers MySQL 模块](https://java.testcontainers.org/modules/databases/mysql/)。
- [Testcontainers 2.0.5 MySQLContainer 源码及实际类型签名](https://raw.githubusercontent.com/testcontainers/testcontainers-java/2.0.5/modules/mysql/src/main/java/org/testcontainers/mysql/MySQLContainer.java)。
