# 认证、方向权限与人员管理实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `backend` 分支实现数据库会话认证、首次强制改密、角色/方向权限、七方向目录、学生与方向负责人管理、学号更正、密码重置和脱敏审计，并保持现有基础工程的安全边界。

**Architecture:** 使用 Spring Security 7.1.1 的无状态 SecurityFilterChain；自定义不透明 Bearer token 过滤器按 SHA-256 摘要查询 MySQL 会话，密码使用 PBKDF2-HMAC-SHA256。Flyway 负责受控版本化迁移，默认 Web 启动不自动迁移；认证/成员/负责人服务在事务中执行锁定、version、幂等和审计。对象级方向范围校验位于服务层，不只依赖 URL 角色注解。

**Tech Stack:** Java 21、Spring Boot 4.1.1、Spring Security 7.1.1、MyBatis-Plus 3.5.17、MySQL 8.0.46、Flyway 12.4.0、Maven Wrapper 3.9.16、现有 JUnit/Testcontainers 测试基础设施。

**Spec:** `docs/superpowers/specs/2026-10-11-auth-member-management-design.md`（用户已确认，提交 `0da8920`）。API 契约：main 分支 `docs/api/openapi.yaml`，提交 `37797e4`。

## Global Constraints

- 只在 `backend` 分支实现；本计划不推送 `main`，不合并 `main`，不写入真实管理员/负责人/学生数据。
- 学生和方向负责人用学号登录；学院管理员账号由外部私密配置初始化；禁止学生自注册和首次登录自动开户。
- 实际初始密码、管理员密码、真实学号和联系人不得进入 Git、迁移 SQL、日志、测试资源或 API 响应。
- 使用随机不透明 Bearer token；数据库仅保存 SHA-256 摘要。禁止 JWT、Cookie 会话、formLogin、httpBasic、默认管理员和 `JSESSIONID`。
- 普通 access token 建议 900 秒，refresh 家族绝对期限 604800 秒，受限改密 token 300 秒且不发 refresh；响应以实际剩余 `expiresIn` 为准。
- PBKDF2-HMAC-SHA256：16 字节随机盐、600000 次迭代、256 位结果，密码长度 12–64 个 Unicode 字符；不静默 trim。
- Flyway 迁移只创建本阶段表、七个方向和无个人数据的 bootstrap 互斥记录；默认 Web 启动不自动迁移，迁移模式必须明确指定独立目标库。
- 本阶段不实现日报、周报、AI、积分、排行榜、月评、自动移出；不注册 `remove`/`restore` 业务路由，不让 pending 规则进入实现。
- 管理写入遵守 main API 契约：POST 业务写入使用 `Idempotency-Key`，PATCH/PUT 使用 `version`；未知字段、学号/role/password/points 等禁止字段返回 400。
- 不改变现有健康端点的原生 JSON、安全详情隐藏、默认绑定地址及已有测试数据库隔离方案。
- 所有 SQL 参数化；先做角色/方向过滤再分页和 count；所有 ID 对外为十进制字符串。

## Review Focus

- 未认证访问已注册受保护接口应返回 401，已认证越权返回 403，受限改密返回 `PASSWORD_CHANGE_REQUIRED`，未知路径仍为 404，错误方法/Content-Type 保留 405/415。
- access/refresh/password/管理员凭据不得出现在数据库明文、日志、异常、审计 summary、响应或 URL；refresh 重放撤销必须提交事务后再返回 401。
- 方向负责人不能通过请求字段跨方向添加/修改/任命或给自己赋权；列表必须先 SQL 权限过滤，不能内存过滤后泄露 total。
- 外部 Hikari 属性、shell profile、已有数据库和迁移重试不能劫持测试；集成测试只能写入自己创建的 Testcontainers 数据库。
- 正式 JAR 只包含精确允许的 `db/migration/V*.sql`，不包含 testsupport、测试 SQL、默认账号或测试启动器；业务 API 的运行状态必须与 main 契约一致。

---

## Task 1: 构建依赖、迁移边界与基础数据库模式

**Files:**
- Modify: `backend/pom.xml`（Spring Security、Flyway、MySQL Flyway 模块及测试依赖）
- Create: `backend/src/main/resources/db/migration/V1__create_auth_member_tables.sql`
- Create: `backend/src/main/resources/db/migration/V2__seed_directions.sql`
- Create: `backend/src/main/resources/db/migration/V3__create_bootstrap_lock.sql`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/config/MigrationProperties.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/config/DatabaseSchemaGuard.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/config/MigrationCommand.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/io/github/dream07code/testsupport/MysqlTestSupport.java`
- Create: `backend/src/test/java/io/github/dream07code/innovationspace/config/MigrationConfigurationTest.java`
- Create: `backend/src/test/java/io/github/dream07code/innovationspace/integration/FlywayMigrationIT.java`
- Create: `backend/src/test/java/io/github/dream07code/innovationspace/integration/UnmigratedDatabaseIT.java`

**Interfaces:**
- `MigrationProperties`: `boolean enabled`, `String mode`, `String requiredVersion`; default Web mode is `disabled`, explicit command mode is `migrate`.
- `DatabaseSchemaGuard`: `void requireCurrentSchema()`; reports safe `SERVICE_UNAVAILABLE` state without leaking JDBC details.
- `MigrationCommand`: non-HTTP `ApplicationRunner`/maintenance entry that runs Flyway only when `app.migration.mode=migrate`; it must not create an administrator.
- Tables: `direction`, `member`, `app_user`, `leader_direction_scope`, `auth_session`, `api_idempotency`, `audit_log`, `bootstrap_lock`; all timestamps `DATETIME(6)` in UTC, optimistic `version` where specified, unique constraints described in the spec.

- [ ] **Step 1: Write failing tests for dependency and migration behavior.** `MigrationConfigurationTest` asserts default mode does not auto-run Flyway, explicit migrate mode is recognized, unknown mode fails safely, and no default admin bean/data exists. `FlywayMigrationIT` starts pinned MySQL, runs the explicit migration path, asserts Flyway history, exactly seven directions, all required tables, no member/user/admin rows, and UTC connection semantics. `UnmigratedDatabaseIT` starts an empty owned database without migration and asserts health is not falsely UP and protected/business paths return 503 rather than 500/200.
- [ ] **Step 2: Run the red tests.**

Run: `newgrp docker -c 'backend/mvnw -f backend/pom.xml -B -ntp -Dtest=MigrationConfigurationTest test'` and `newgrp docker -c 'backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=FlywayMigrationIT,UnmigratedDatabaseIT verify'`

Expected: compilation or assertions fail because migration dependencies, SQL, properties, and schema guard do not yet exist. Docker/image failures are recorded separately and do not count as behavior RED.

- [ ] **Step 3: Add dependencies and exact SQL migrations.** Add Boot-managed `spring-boot-starter-security`, `flyway-core`, and `flyway-mysql`; keep `spring.sql.init.mode=never`. Write foreign keys, unique constraints, enum/string status checks, indexes for account/student number/session hash/direction scope/audit time, and no personal seed data. `V1` creates tables, `V2` inserts only the seven fixed directions, `V3` inserts one non-personal bootstrap lock row.
- [ ] **Step 4: Implement explicit migration and schema guard.** Disable Boot auto-migration for normal Web mode. Make the maintenance mode run Flyway against the configured target and terminate with a nonzero failure without starting HTTP. Make normal application context verify the required schema version; before schema is current, health reports dependency failure and business services can return `SERVICE_UNAVAILABLE`. Do not log JDBC URL/password or migration SQL parameters.
- [ ] **Step 5: Run Task 1 verification.**

Run: `newgrp docker -c 'backend/mvnw -f backend/pom.xml -B -ntp verify'`

Expected: existing 25 unit and 20 integration tests plus the migration tests pass, no skipped integration tests, and no production SQL outside the exact migration directory is packaged.

- [ ] **Step 6: Commit.** `git add backend/pom.xml backend/src/main backend/src/test ... && git commit -m "feat: add controlled auth schema migrations"`

## Task 2: Domain mappings、密码、Token、会话和幂等基础服务

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/domain/{direction,member,user,auth,audit}/...`（实体/枚举/Mapper/DTO 映射，按职责拆分）
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/security/PasswordConfiguration.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/security/PasswordService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/security/TokenService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/security/SessionService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/security/IdempotencyService.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/security/{PasswordServiceTest,TokenServiceTest,SessionServiceTest,IdempotencyServiceTest}.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/AuthenticationPersistenceIT.java`

**Interfaces:**
- `PasswordService`: `String encode(CharSequence raw)`, `boolean matches(CharSequence raw, String encoded)`, `void validateNewPassword(String raw, String currentHash, String initialPassword)`; no raw password logging.
- `TokenService`: `GeneratedTokenPair issueNormal(long userId, long authRevision)`, `GeneratedToken issuePasswordChange(long userId, long authRevision)`, `String digest(String rawToken)`, `TokenEnvelope validateSyntax(String rawToken)`.
- `SessionService`: `SessionPrincipal authenticateAccess(String rawToken)`, `RefreshResult rotateRefresh(String rawRefreshToken)`, `void revokeSession(String rawAccessToken, String reason)`, `void revokeAllForUser(long userId, String reason)`.
- `IdempotencyService`: `IdempotencyDecision acquire(long actorUserId, String method, String normalizedPath, String key, String requestDigest)`, `void complete(...)`, `void releaseOnFailure(...)`; login/refresh/logout do not use it.
- `SessionPrincipal` contains user ID, optional member ID, role, managed direction IDs, password-change flag, auth revision, and session ID; it never contains raw tokens or password hashes in a public DTO.

- [ ] **Step 1: Write failing unit tests.** Pin PBKDF2 parameters through encoded format/matching and reject blank/short/long/current/shared-initial passwords. Pin token entropy/URL-safe syntax, digest non-reversibility, access expiry, refresh absolute family expiry, restricted session without refresh, and no raw token in `toString`. Pin refresh rotation, concurrent single-winner semantics, replay family revocation after transaction commit, user auth revision invalidation, logout/revoke-all, and idempotency same-key replay/conflict/failure release.
- [ ] **Step 2: Run RED.** `backend/mvnw -B -ntp -Dtest=PasswordServiceTest,TokenServiceTest,SessionServiceTest,IdempotencyServiceTest test` should fail to compile or assert missing behavior.
- [ ] **Step 3: Implement minimal domain mappings and services.** Use MyBatis-Plus mappers with parameterized SQL and `FOR UPDATE` where lock order requires it. Configure PBKDF2-HMAC-SHA256 with the exact spec values. Use `SecureRandom` 32-byte tokens, Base64 URL-safe no padding, SHA-256 digest, and no raw-secret logging. Keep refresh replay revocation in a committed transaction boundary; convert the post-commit failure result to 401 at the HTTP boundary.
- [ ] **Step 4: Add persistence integration tests.** `AuthenticationPersistenceIT` runs migration, creates synthetic users/sessions, asserts only hashes are stored, unique constraints hold, token/session expiration and revocation survive a new service instance, and idempotency records do not store password/token data.
- [ ] **Step 5: Run Task 2 green.** `newgrp docker -c 'backend/mvnw -f backend/pom.xml -B -ntp -Dtest=PasswordServiceTest,TokenServiceTest,SessionServiceTest,IdempotencyServiceTest -Dit.test=AuthenticationPersistenceIT verify'` expected all pass.
- [ ] **Step 6: Commit.** `git commit -m "feat: add password token session and idempotency services"`

## Task 3: Spring Security HTTP boundary and authentication API

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/security/SecurityConfiguration.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/security/BearerTokenAuthenticationFilter.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/security/SessionPrincipal.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/security/RateLimitService.java`
- Create/Modify: `backend/src/main/java/io/github/dream07code/innovationspace/common/api/ErrorCode.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/web/AuthController.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/web/AuthDtos.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/web/ApiSecurityExceptionHandler.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/security/SecurityConfigurationTest.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/AuthenticationHttpIT.java`

**Interfaces:**
- `SecurityFilterChain apiSecurityFilterChain(HttpSecurity http, BearerTokenAuthenticationFilter bearer, ...)` is stateless, disables formLogin/httpBasic/default login page, creates no session, and permits health/login/refresh only.
- `BearerTokenAuthenticationFilter` creates authentication only from `Authorization: Bearer`; it does not create accounts, query URL tokens, or leak failure details.
- `AuthController`: `POST /api/v1/auth/login`, `POST /api/v1/auth/refresh`, `POST /api/v1/auth/logout`, `GET /api/v1/auth/me`, `PUT /api/v1/auth/password`; response DTOs match `Session`, `Me`, `EmptyResponse` and `Cache-Control: no-store` where specified.
- `RateLimitService`: bounded in-process buckets for login by IP+account digest, refresh by IP+token digest, password change by user; returns retry-after seconds without trusting raw forwarded headers.

- [ ] **Step 1: Write red MockMvc tests.** Assert no JSESSIONID/Cookie; login success/invalid credentials/cache-control; restricted first-login allowlist; unauthenticated 401, forbidden 403, `PASSWORD_CHANGE_REQUIRED` 403; refresh rotation/replay; logout; password change revokes old access; unknown path 404 and wrong method/content-type preserve 405/415; rate limit 429 with Retry-After; no raw credentials in body/log.
- [ ] **Step 2: Run RED.** `backend/mvnw -B -ntp -Dtest=SecurityConfigurationTest,AuthenticationHttpIT test` expected missing security chain/controller/error code failures.
- [ ] **Step 3: Implement security boundary.** Add stateless filter chain, explicit public routes, no cookie/session, security handlers returning `ApiResponse` with correct HTTP status, restricted-token allowlist, and bounded rate limiting. Ensure unknown routes are not converted into authentication failures.
- [ ] **Step 4: Run HTTP green.** `backend/mvnw -B -ntp -Dtest=SecurityConfigurationTest,AuthenticationHttpIT test` expected all assertions pass; then run `verify` with Testcontainers.
- [ ] **Step 5: Commit.** `git commit -m "feat: add database session authentication APIs"`

## Task 4: Direction catalog and student member management

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/direction/{DirectionController,DirectionService,DirectionMapper,DirectionDtos}.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/member/{MemberController,MemberService,MemberMapper,MemberDtos,MemberScopePolicy}.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/DirectionAndMemberHttpIT.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/member/MemberScopePolicyTest.java`

**Interfaces:**
- `DirectionController`: `GET /api/v1/directions`, `GET /api/v1/directions/{directionId}`; fixed seven directions, stable order, no PII.
- `MemberService`: `Page<MemberResponse> list(MemberQuery query, SessionPrincipal actor)`, `MemberResponse create(CreateMemberRequest request, SessionPrincipal actor, String idempotencyKey)`, `MemberResponse get(long memberId, SessionPrincipal actor)`, `MemberResponse update(long memberId, UpdateMemberRequest request, SessionPrincipal actor)`, `MemberResponse correctStudentNo(...)`, `MemberResponse resetPassword(...)`.
- Create always creates `STUDENT` with `totalPoints=0` and `mustChangePassword=true`; request cannot set role/password/points/managed scopes. Leader may target only own scope; admin all directions.
- Update supports true PATCH semantics (`version` plus at least one mutable field), never accepts studentNo/role/password/points/managedDirectionIds; admin can change student direction, leader cannot.
- SQL applies scope before `Page` count/data; student cannot list directory, query others, or submit arbitrary memberId.

- [ ] **Step 1: Write red policy and HTTP tests.** Cover fixed seven directions; admin all-direction list/create; leader own direction create/list/update; cross-direction 403 or object-hiding 404; student no member list/create; duplicate student number/account 409; PATCH partial/null clear and forbidden fields 400; version conflict 409; idempotency replay; ranking/PII fields absent; SQL count reflects filtered rows.
- [ ] **Step 2: Run RED.** `newgrp docker -c 'backend/mvnw -B -ntp -Dit.test=DirectionAndMemberHttpIT verify'` expected missing controllers/mappers/services.
- [ ] **Step 3: Implement directions and members.** Add DTO validation, service transaction order, SQL scope filters, row locks, optimistic versions, idempotency integration, initial password from `MEMBER_INITIAL_PASSWORD` without response/logging, and audit event hooks.
- [ ] **Step 4: Run green and inspect response.** Repeat targeted verify; assert every response matches main OpenAPI schemas and excludes password hashes/tokens/contact for unauthorized callers.
- [ ] **Step 5: Commit.** `git commit -m "feat: add direction catalog and member management"`

## Task 5: Leader management, student-number correction, password reset and audit API

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/leader/LeaderController.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/leader/LeaderService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/audit/AuditController.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/audit/AuditService.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/member/MemberService.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/LeaderAndAuditHttpIT.java`

**Interfaces:**
- `LeaderService`: `Page<MemberResponse> list(...)`, `MemberResponse appoint(AppointLeaderRequest request, SessionPrincipal admin, String key)`, `MemberResponse update(...)`, `MemberResponse revoke(...)`.
- `POST /direction-leaders` can only promote an existing ACTIVE STUDENT with at least one direction scope; it does not create another account. Revoke returns the same member as STUDENT and clears current scopes while retaining audit history.
- Admin-only account correction checks global account/student-number uniqueness, preserves member/user IDs and historical references, revokes all sessions, and records restricted old/new values.
- Admin-only password reset reads `MEMBER_INITIAL_PASSWORD`, never accepts/returns a password, sets `mustChangePassword=true`, and revokes sessions.
- `GET /api/v1/audits` returns paged sanitized summaries only; no audit detail, password, token, report text or unrestricted contact data.

- [ ] **Step 1: Write red tests.** Cover promotion/revocation, empty scope rejection, leader cannot appoint/update/revoke, admin updates leader profile/scope/status, immediate old-token revocation, correction uniqueness/history/audit, reset behavior, audit visibility and PII redaction, concurrent admin operations and version conflicts.
- [ ] **Step 2: Run RED.** `newgrp docker -c 'backend/mvnw -B -ntp -Dit.test=LeaderAndAuditHttpIT verify'` expected missing implementation.
- [ ] **Step 3: Implement service transactions and audit.** Enforce lock order actor/target by numeric ID, scope checks at transaction time, committed refresh-replay/security audit outcomes, and response mapping to the existing envelope/status contract.
- [ ] **Step 4: Run green.** Targeted verify plus `git diff --check`; expected all leader/audit tests pass with no sensitive output.
- [ ] **Step 5: Commit.** `git commit -m "feat: add leader administration and audit APIs"`

## Task 6: Admin bootstrap, migration CLI, readiness and package boundaries

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/bootstrap/AdminBootstrapCommand.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/config/MigrationCommand.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/config/DatabaseSchemaGuard.java`
- Modify: `backend/src/main/resources/application.yml`
- Create: `backend/src/test/java/io/github/dream07code/innovationspace/integration/AdminBootstrapIT.java`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/JarBoundaryIT.java`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/PackagedApplicationIT.java`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/HealthEndpointIT.java`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/DevApiDocumentationIT.java`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/DefaultApiDocumentationIT.java`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/ProdApiDocumentationIT.java`

**Interfaces:**
- `AdminBootstrapCommand`: non-HTTP maintenance mode; reads `ADMIN_ACCOUNT`, `ADMIN_PASSWORD`, `ADMIN_DISPLAY_NAME`; missing values fail; existing admin is not overwritten; concurrent invocation cannot create two admins; output never includes account/password/hash.
- Normal Web mode verifies current Flyway version and refuses protected business traffic if missing; it does not mutate schema.
- `JarBoundaryIT` permits only `BOOT-INF/classes/db/migration/V*.sql` and production classes/resources; testsupport/browser/probe/test SQL/credentials remain forbidden.
- Packaged tests cover migration-before-service and default/dev/prod docs plus implemented auth/member paths; no default account/member data appears.

- [ ] **Step 1: Write red boundary tests.** Assert admin bootstrap missing/duplicate/concurrent cases, no password in output, migration mode no HTTP, unmigrated readiness failure, exact JAR migration whitelist, and no default admin/member rows.
- [ ] **Step 2: Run RED.** `newgrp docker -c 'backend/mvnw -B -ntp -Dit.test=AdminBootstrapIT,JarBoundaryIT,PackagedApplicationIT verify'` expected failures until command/readiness/package assertions are updated.
- [ ] **Step 3: Implement maintenance/bootstrap boundary.** Keep normal Web start non-mutating, add explicit maintenance properties and safe exit status, use bootstrap lock transaction, and update package tests to the intended new SQL boundary. Never add real credentials to test fixtures.
- [ ] **Step 4: Run green.** Targeted packaged verification and inspect JAR entries. Expected no test fixture leakage and no false UP on unmigrated databases.
- [ ] **Step 5: Commit.** `git commit -m "feat: add controlled admin bootstrap and schema readiness"`

## Task 7: Contract synchronization, full verification and delivery

**Files:**
- Modify: `backend/src/main/resources/application.yml` and profile YAML only where auth/migration settings need explicit safe defaults.
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/config/OpenApiConfiguration.java` only if generated dev docs need metadata/security.
- Modify: `docs/api/openapi.yaml` and `docs/api/README.md` only if implementation intentionally changes a contract; otherwise record `implemented` status in the backend delivery notes without silently changing main.
- Create: `backend/README.md` authentication/migration/admin bootstrap sections.
- Create: `docs/superpowers/reports/2026-10-11-auth-member-management-verification.md`.

- [ ] **Step 1: Write contract smoke tests.** Verify implemented routes, schemas, HTTP statuses, restricted-token allowlist, no self-register route, and generated OpenAPI paths/responses. Verify all unimplemented downstream routes remain absent/404.
- [ ] **Step 2: Run full verification.** Under poisoned `SPRING_PROFILES_ACTIVE=dev` and Hikari-specific properties run `newgrp docker -c 'backend/mvnw -f backend/pom.xml -B -ntp clean verify'`; expected all unit/integration tests pass with zero failures/errors/skips and reports include migration/auth/member coverage.
- [ ] **Step 3: Security/package scans.** `git diff --check`; inspect dependency tree for no duplicate security/Flyway version overrides, scan staged files for real credentials/private keys, inspect JAR exact migration whitelist and absence of testsupport/browser/secret files.
- [ ] **Step 4: Update operational documentation.** Explain migrate-before-start, admin bootstrap inputs without values, session/token behavior, rate limits, initial password delivery risk, and that downstream report/points/monthly routes are not implemented.
- [ ] **Step 5: Final review and commit.** Use one fresh whole-branch code review after all tasks; fix Critical/Important findings with RED→GREEN, record deferred minors/rulings, run full verify again, and commit `test: verify authentication and member management delivery`.
- [ ] **Step 6: Finish.** Keep backend changes on `backend` unless user separately requests pushing/merging. Read back remote status only after explicit push authorization; do not shut down the computer.

## Acceptance Matrix

| Requirement | Evidence |
| --- | --- |
| Login/first password change | Task 2–3 unit + HTTP + MySQL tests |
| Opaque tokens and refresh replay | Task 2 persistence + Task 3 HTTP tests |
| No self-registration | Task 3/4 route and HTTP 404/403 tests |
| Direction-scoped manager permissions | Task 4–5 scope tests and filtered pagination |
| Student number correction | Task 5 uniqueness/history/session/audit tests |
| Leader administration | Task 5 promotion/revoke/update tests |
| Admin initialization safety | Task 6 maintenance/bootstrap tests |
| Schema migration/readiness | Task 1/6 Flyway and packaged tests |
| Secret/JAR boundary | Task 6/7 scans and JarBoundaryIT |
| Existing baseline safety | Task 1–7 retained health/pagination/error/browser tests |

## Execution Notes

- The current backend branch is clean at `0da8920` after the approved design commits; start from this branch state.
- Keep `OwnedDataSourceConfiguration` and `DatabaseIsolationIT`; never remove them to make ambient datasource properties control tests.
- No real administrator password or direction leader data is available yet; use synthetic values only.
- If MySQL/Docker is unavailable, proceed with unit tests but do not mark integration/package acceptance complete.
