# 日报周报、可配置评分、积分与排行榜实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现日报/周报提交、可配置 AI 评分、积分流水、个人积分和方向/总排行榜，并保持月度淘汰与自动移出未启用。

**Architecture:** 在现有认证/成员基础上新增报告、报告版本、评分任务、积分流水和复核基础表。报告提交事务只负责保存有效提交、保底策略和评分任务；评分 Worker 锁任务后在事务外调用可插拔 provider，再以报告版本和人工状态做二次校验。AI provider 通过配置 registry 选择，默认关闭，OpenAI-compatible HTTP 适配器不绑定具体厂商 SDK。

**Tech Stack:** Java 21、Spring Boot 4.1.1、MyBatis-Plus 3.5.17、MySQL 8.0.46、Flyway 12.4.0、现有 Spring Security 会话、JDK HTTP Client、JUnit/Testcontainers。

**Spec:** `docs/superpowers/specs/2026-10-11-report-scoring-points-ranking-design.md`（用户已确认，提交 `0cdc340`）。API 契约以 `docs/api/openapi.yaml` 为准，只有真实注册和验收的业务路径才更新为 implemented。

## Global Constraints

- 只在 `backend` 分支实现；不推送或修改 `main`，不写入真实报告、学生、负责人、管理员或 AI Key。
- `AI_SCORING_ENABLED=false` 是安全默认；enabled 时 provider、base URL、model、key 必须完整，公网 base URL 必须 HTTPS，本地测试才允许 loopback HTTP。
- `AI_API_KEY` 只从外部私密配置读取，不进入数据库、Git、日志、异常、任务、审计或响应；模型输入默认不含姓名、学号、联系方式、角色和 token。
- Provider 只返回经过后端校验的 `score=1..3` 和受限长度 reason；非法响应、超时、网络错误和 5xx 进入有限重试/人工复核，不当作 0 分。
- 草稿不计分；正式提交和 AI 完成分离；同一成员同一日报日期/周报周期唯一；重复提交、任务、回调和积分事件幂等。
- 报告 PATCH 必须合并后校验正式内容；旧版本评分不能覆盖新版本，MANUAL 结果优先。
- 积分只能由 `point_ledger` 净和产生，禁止直接写累计分；排行榜只返回最小展示字段，不返回学号、联系方式或报告正文。
- 本阶段不实现月度评定、自动移出、恢复、报告作废 HTTP 接口、直接加减积分、公开人工评分接口；月度相关路由继续不存在或保持 pending。
- 继续使用现有 `OwnedDataSourceConfiguration`、数据库隔离回归、迁移模式和 JAR 边界；测试只使用合成内容及 loopback fake provider。

## Review Focus

- 报告唯一键、版本和 Idempotency-Key 在重复创建、重复提交、并发提交和 worker 重试时不能产生第二份报告或第二笔积分。
- AI provider 切换、配置缺失、非法 JSON、越界分数、超时、旧版本迟到结果不能泄露 Key、覆盖当前结果或把失败误记为 0 分。
- 已提交报告 PATCH 清空学习内容/收获时必须 400 且报告、积分、任务全部保持不变；草稿仍可不完整。
- 负责人/管理员查看报告、积分流水和排行榜必须先 SQL 权限过滤再分页；草稿、联系方式、学号和正文不能越权泄露。
- 同分排行榜按竞赛排名 1、1、3；月份归属按首次正式提交业务时间，不按评分回调时间漂移；负责人未决月评不进入本阶段实现。

---

## Task 1: 报告、版本、评分任务与积分迁移

**Files:**
- Modify: `backend/src/main/resources/db/migration/V4__create_report_scoring_points_tables.sql`
- Modify: `backend/src/main/java/io/github/dream07code/innovationSpace/config/MapperConfiguration.java`（仅在 mapper 扫描需要调整时）
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ReportType.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ReportStatus.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ScoreStatus.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ReportEntity.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ReportVersionEntity.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ScoringTaskEntity.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/points/PointLedgerEntity.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ReportMapper.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/points/PointLedgerMapper.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/ReportPointsMigrationIT.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/report/ReportCyclePolicyTest.java`

**Interfaces:**
- Migration creates `report`, `report_version`, `report_scoring_task`, `point_ledger`, and `report_review` with UTC `DATETIME(6)`, unique member/type/period, report-version/task/event idempotency indexes, and no personal seed data.
- `ReportPeriodPolicy`: `LocalDate dailyPeriod(Clock clock)`, `WeekPeriod weeklyPeriod(Clock clock)`, `boolean isEditable(...)`; all business dates use injected Asia/Shanghai Clock.
- `ReportMapper`: parameterized methods for find by owner/period, scoped page query, lock current version, insert draft/version, submit transition, and update score state.
- `PointLedgerMapper`: insert idempotent ledger event, sum by member/month, scoped ledger page, and ranking aggregation query; no direct total update.

- [ ] **Step 1: Write RED tests.** Assert V4 tables/indexes exist only after explicit migration, no report/user rows are seeded, daily/weekly period boundaries use Shanghai time, duplicate member+period is rejected, and ledger schema supports signed deltas/idempotency.
- [ ] **Step 2: Run RED.** `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=ReportPointsMigrationIT verify` and `backend/mvnw -f backend/pom.xml -B -ntp -Dtest=ReportCyclePolicyTest test`; expected missing migration/entities/policy.
- [ ] **Step 3: Implement V4 and mappings.** Add strict foreign keys to member/direction, report version hashes, task status/attempt fields, ledger source/effective time/rule version and review version fields. Use only parameterized SQL/MyBatis mappings; do not store provider response or Key.
- [ ] **Step 4: Run GREEN.** Repeat targeted tests and existing migration suite; expected V1–V4 clean migration, repeatable migration and zero personal seed rows.
- [ ] **Step 5: Commit.** `git commit -m "feat: add report scoring and points schema"`

## Task 2: Report draft/submit/query APIs

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ReportDtos.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ReportScopePolicy.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ReportService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/report/ReportController.java`
- Create: `backend/src/test/java/io/github/dream07code/innovationspace/integration/ReportHttpIT.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/report/ReportScopePolicyTest.java`

**Interfaces:**
- `ReportService`: `Page<ReportResponse> list(ReportQuery, SessionPrincipal)`, `ReportResponse createDraft(CreateReportRequest, SessionPrincipal, String key)`, `ReportResponse get(long reportId, SessionPrincipal)`, `ReportResponse patch(long reportId, PatchReportRequest, SessionPrincipal)`, `ReportResponse submit(long reportId, long version, SessionPrincipal)`.
- Register daily and weekly paths from the approved contract; do not register monthly/remove/restore/report-delete paths.
- Create derives member from principal and rejects client memberId. Daily uses server current report date; weekly uses current Shanghai Monday–Sunday period. Explicit date/period fields must match the allowed window.
- Draft content may be incomplete; submit validates merged daily `learningContent/gains` or weekly `summary/achievements` nonblank and length limits.
- Submit transaction locks report/member, checks version and active membership, transitions once, adds configured submission ledger event once, and creates one QUEUED task. If points policy is `PENDING_CONFIRMATION`, return `503 RULES_NOT_CONFIGURED` rather than guessing.
- PATCH supports omission/null semantics, rejects studentNo/role/points/memberId/unknown fields, and for submitted reports validates merged complete content before changing anything. Manual final reports are not editable.

- [ ] **Step 1: Write RED HTTP/policy tests.** Cover draft creation, daily/weekly uniqueness, student ownership, leader/admin scope, no other draft visibility, valid submit, invalid empty submit, duplicate submit, version conflict, idempotency replay, submitted PATCH merge validation, and no unregistered report-delete/monthly routes.
- [ ] **Step 2: Run RED.** `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=ReportHttpIT verify` and `backend/mvnw -f backend/pom.xml -B -ntp -Dtest=ReportScopePolicyTest test`.
- [ ] **Step 3: Implement report service/controllers.** Reuse existing SessionPrincipal, MemberScopePolicy, IdempotencyService and DatabaseSchemaGuard. Apply scope predicates before count/page and map responses without password/contact leaks.
- [ ] **Step 4: Run GREEN.** Targeted tests plus `ContractAssertions` against actual report responses; expected submit is persisted before any provider call.
- [ ] **Step 5: Commit.** `git commit -m "feat: add daily and weekly report APIs"`

## Task 3: Configurable provider registry and asynchronous scoring worker

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/ScoringProperties.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/ScoringInput.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/ProviderScore.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/ReportScoringProvider.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/ScoringProviderRegistry.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/MockReportScoringProvider.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/OpenAiCompatibleReportScoringProvider.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/ScoringWorker.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/ScoringRubric.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/scoring/{ScoringPropertiesTest,ProviderRegistryTest,ProviderResponseValidationTest,ScoringWorkerTest}.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/ScoringProviderIT.java`

**Interfaces:**
- `ScoringProperties`: validated `enabled`, `provider`, `baseUrl`, `apiKey`, `model`, connect/request timeout, maxRetries, maxConcurrency; `toString()` redacts key and URL credentials. No default real provider/key.
- `ReportScoringProvider`: `ProviderScore score(ScoringInput input)`. Provider registry selects by `AI_PROVIDER`; disabled returns no external call.
- `OpenAiCompatibleReportScoringProvider` uses JDK HTTP client, configured HTTPS/loopback policy, no URL key, timeout, structured JSON DTO and no raw response logging. It sends type/version/rubric/content only.
- `ScoringWorker`: claim queued task under lock, call provider outside transaction, then lock report/task/version and transition to SCORED/RETRYING/REVIEW_REQUIRED/STALE. It invokes `PointLedgerService` for only the necessary score delta.
- `ScoringRubric`: versioned text and input-safe delimiters; report content is data, never instructions.

- [ ] **Step 1: Write RED unit tests.** Disabled config does not require key; enabled config rejects missing key/model/base URL, public HTTP base URL, invalid timeout/concurrency. Mock returns valid 1/2/3; provider rejects malformed JSON, score 0/4, missing reason, giant reason and leaked secret. Registry rejects unknown provider. Worker retries timeout/5xx, transitions final failure to REVIEW_REQUIRED, ignores stale/manual versions, and never logs/stores key/raw provider response.
- [ ] **Step 2: Run RED.** `backend/mvnw -f backend/pom.xml -B -ntp -Dtest=ScoringPropertiesTest,ProviderRegistryTest,ProviderResponseValidationTest,ScoringWorkerTest test`.
- [ ] **Step 3: Implement providers and worker.** Load configuration at startup, construct only enabled provider, use bounded executor/concurrency and scheduled claim polling. Keep provider calls outside DB transactions; revalidate task/report state before scoring and ledger delta.
- [ ] **Step 4: Add fake HTTP integration tests.** `ScoringProviderIT` starts an owned loopback fake server, asserts no name/studentNo/contact/token in request, exercises valid/invalid/timeout/500 responses, and confirms no external network or real key.
- [ ] **Step 5: Run GREEN.** Targeted provider/worker/fake-server tests and report submit-to-score flow pass; disabled production profile makes no outbound call.
- [ ] **Step 6: Commit.** `git commit -m "feat: add configurable report scoring providers"`

## Task 4: Point ledger service and rankings

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/points/PointsPolicyProperties.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/points/PointLedgerService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/points/RankingService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/points/PointsController.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/points/RankingController.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationSpace/points/{PointLedgerServiceTest,RankingServiceTest}.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/PointsRankingHttpIT.java`

**Interfaces:**
- `PointsPolicyProperties`: `status=PENDING_CONFIRMATION/READY`, `mode=FINAL_SCORE/BASE_PLUS_AI`, base values and max final score; no hidden defaults in production.
- `PointLedgerService`: `appendIfAbsent(PointEvent event)`, `long total(long memberId, YearMonth month)`, `Page<PointEntry> ledger(PointQuery, SessionPrincipal)`, `long applyScoreDelta(...)`; no direct total setter.
- `RankingService`: `Page<RankingEntry> overall(PeriodQuery, SessionPrincipal)`, `Page<RankingEntry> direction(long directionId, PeriodQuery, SessionPrincipal)`, `MyRanking me(...)`; SQL aggregation includes ACTIVE members only, uses `RANK()`/equivalent rank 1,1,3 and deterministic member ID ordering.
- `PointsController` registers `/points/me`, `/points/ledger`; `RankingController` registers `/rankings/overall`, `/rankings/directions/{directionId}`, `/rankings/me`.

- [ ] **Step 1: Write RED tests.** Cover base point once, score delta +2/-2, manual/old version idempotency, monthly effectiveAt not callback time, no direct point mutation, leader scope, student self-only, ranking fields exclude PII, all-time/month/direction filters, inactive members excluded, ties 1/1/3, page total before data.
- [ ] **Step 2: Run RED.** `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=PointsRankingHttpIT verify` and unit tests.
- [ ] **Step 3: Implement ledger/ranking services and APIs.** Ensure report worker invokes one transactional ledger operation; SQL aggregation and scope predicates run before pagination/count; return IDs as strings and `finalScore=null` when pending.
- [ ] **Step 4: Run GREEN.** Targeted tests and API contract body assertions pass; no monthly/remove/task routes appear.
- [ ] **Step 5: Commit.** `git commit -m "feat: add points ledger and ranking APIs"`

## Task 5: Contract sync, operational docs and full delivery

**Files:**
- Modify: `backend/src/main/resources/api-contract.yaml`, `backend/src/main/java/io/github/dream07code/innovationspace/config/OpenApiConfiguration.java`.
- Modify: `docs/api/openapi.yaml`, `docs/api/README.md`, `docs/api/VALIDATION.md` only for intentional implemented-path/status/provider-config changes; do not update `main` in this task.
- Modify: `backend/README.md` with report/AI/provider/Key/point/ranking operation notes.
- Create: `docs/superpowers/reports/2026-10-11-report-scoring-points-ranking-verification.md`.
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/ImplementedApiContractIT.java`, `JarBoundaryIT.java`, `PackagedApplicationIT.java`.

- [ ] **Step 1: Write RED contract/package tests.** Assert implemented report/points/ranking paths and schemas, omitted monthly/remove paths, provider config not exposed, runtime contract equals approved design for implemented paths, migration JAR whitelist and no keys/provider responses.
- [ ] **Step 2: Run full verification.** Poison profile/Hikari settings and execute `backend/mvnw -f backend/pom.xml -B -ntp clean verify`; expected all previous plus new report/scoring/points tests pass with zero failures/errors/skips.
- [ ] **Step 3: Run provider/document scans.** Validate OpenAPI 3.0.3, examples, refs, `AI_API_KEY`/Authorization/private-key scan, dependency tree, diff check, JAR boundary, and fake provider outbound assertions.
- [ ] **Step 4: Update docs/report.** Explain config examples without secrets, provider compatibility, disabled/default behavior, scoring failure states, report period rules, ledger/ranking semantics, and all unimplemented monthly/automatic-removal scope.
- [ ] **Step 5: One final whole-branch review.** Review against this spec/plan; fix Critical/Important findings with RED→GREEN, record deferred minors/rulings, rerun clean verify and browser probe.
- [ ] **Step 6: Commit.** `git commit -m "test: verify reports scoring points and rankings delivery"`; keep changes on `backend` until separately authorized.

## Acceptance Matrix

| Requirement | Evidence |
| --- | --- |
| Daily/weekly draft/submit/edit | Task 2 report HTTP and period tests |
| Provider configurable and secret-safe | Task 3 properties/provider/fake HTTP tests |
| Async retry/failure/version behavior | Task 3 worker and integration tests |
| Score delta and idempotent ledger | Task 1/3/4 MySQL tests |
| Personal/direction/overall rankings | Task 4 ranking SQL and HTTP tests |
| Scope/privacy/pagination | Task 2/4 filtered integration tests |
| No monthly/automatic removal | Task 5 route and OpenAPI assertions |
| Existing auth/migration/package safety | Full verify and retained previous suites |

## Execution Notes

- Start from current clean `backend` branch after the auth/member delivery. Do not merge `main`; the Draft PR remains separate.
- Preserve `OwnedDataSourceConfiguration`, `DatabaseIsolationIT`, `MapperConfiguration`, contract body assertions and exact maintenance-mode boundary.
- No real AI provider key, administrator, leader, student or report content is available; use mock/fake values only.
- If no provider is configured, do not mark production AI scoring as available; provider integration remains disabled and safe.
