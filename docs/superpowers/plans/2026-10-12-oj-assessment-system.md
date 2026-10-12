# OJ 考核系统 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 先冻结前后端 API 契约，再实现独立 OJ 考核、代码提交、开源 OJ 适配、AI/人工评分和独立结果系统；不改变现有日报周报积分。

**Architecture:** 以 `Assessment/Problem/Submission/OjJudgeResult/AiEvaluation/Review/Result` 为独立领域，通过 `OjAdapter` 隔离 GitHub 开源 OJ，通过现有可配置 AI Provider 生成质量评分，人工 review 覆盖 AI 结果。前端和后端先依据 `planned` OpenAPI DTO 并行开发。

**Tech Stack:** Java 21、Spring Boot 4.1.1、MyBatis-Plus、MySQL 8.0.46、Flyway、Spring Security、JDK HTTP Client、JUnit/Testcontainers、现有 Knife4j/OpenAPI 流程。

**Spec:** `docs/superpowers/specs/2026-10-12-oj-assessment-system-design.md`

## Global Constraints

- 只在 `backend` 分支实现；不合并 `main`，不覆盖已有报告/积分功能。
- 考核结果独立于日报/周报积分，禁止写入 `point_ledger` 或影响现有排行榜。
- OJ 仅通过 `OjAdapter` 接入，不允许前端访问 OJ 私有 API、管理凭据或任意执行 URL。
- 学生提交代码和私有测试数据必须最小权限保存，不进入普通响应、日志或审计。
- 所有写接口要求 Idempotency-Key；ID 为字符串。
- 草稿、已发布、关闭、归档状态和题目版本必须可追踪。
- OJ 判定、AI 评分、人工 review 分开保存；人工 review 优先于 AI。
- 先以 `planned` 状态发布契约，只有真实注册和验收后才改为 `implemented`。
- 未完成的 OJ 产品选型、沙箱、语言范围和 AI rubric 不得在前端硬编码。

## Review Focus

1. 前端拿到契约后能否独立完成页面、Mock 和错误处理，而不依赖后端 OJ 私有字段。  
   Test: `OJApiContractIT` validates planned DTOs and state enums.
2. 学生只能读取自己的提交，负责人只能读取授权方向，管理员才能发布/关闭考核。  
   Test: `AssessmentPermissionIT` covers role matrix.
3. 代码正文、测试数据、OJ 异常和 API Key 不能泄露到普通响应/日志/审计。  
   Test: `AssessmentPrivacyIT` and secret scans.
4. OJ 超时、编译失败、运行错误和内部错误都映射到安全 verdict，不伪造通过或0分。  
   Test: `OjAdapterIT` fake adapter matrix.
5. 人工 review、AI 评分和迟到结果的优先级、版本和幂等性必须稳定。  
   Test: `AssessmentScoringIT` and `AssessmentReviewIT`.

---

## Task 1: 冻结 OpenAPI 契约与 DTO

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Modify: `docs/api/README.md`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/OJApiContractIT.java`

**Interfaces:**
- Produces: `planned` paths listed in the approved spec: assessments, problems, submissions, AI evaluation, reviews, results.
- DTOs: `Assessment`, `AssessmentProblem`, `AssessmentSubmission`, `OjJudgeResult`, `AiEvaluation`, `AssessmentReview`, `AssessmentResult`, corresponding request/page/response schemas。

- [ ] **Step 1: Write RED contract tests**

Assert paths exist with `x-implementation-status: planned`; request schemas reject extra fields; IDs are strings; state enums and review/AI/result fields match the spec; existing report/monthly paths remain unchanged.

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=OJApiContractIT verify`  
Expected: missing planned paths/DTOs.

- [ ] **Step 2: Implement contract only**

Add YAML paths and schemas to both contract copies. Do not implement Java controllers yet.

- [ ] **Step 3: Run GREEN**

Run: `OJApiContractIT verify` and compare both YAML files byte-for-byte.

- [ ] **Step 4: Commit**

```bash
git add docs backend/src/main/resources backend/src/test/java
git commit -m "docs: reserve OJ assessment API contract"
```

---

## Task 2: 领域状态机与数据库迁移

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/*`
- Create: `backend/src/main/resources/db/migration/V6__create_assessment_system.sql`
- Test: `AssessmentStatePolicyTest`, `AssessmentSchemaIT`

**Interfaces:**
- Produces: `AssessmentStatus`, `ProblemStatus`, `SubmissionStatus`, `ReviewStatus`, `AssessmentStatePolicy`.
- Tables: assessment, assessment_problem, assessment_submission, oj_judge_result, ai_evaluation, assessment_review, assessment_result, assessment_audit。

- [ ] **Step 1: Write RED schema/state tests**

Assert state transitions, unique problem/submission relationships, no personal seed rows, no `point_ledger` dependency, and code/test-data fields are not exposed by entity mapping.

- [ ] **Step 2: Implement V6 schema and policies**

Use strict foreign keys and indexes for assessment/problem/member/status/time. Keep code storage reference separate from ordinary response DTOs.

- [ ] **Step 3: Run GREEN**

Run: `AssessmentSchemaIT` and migration boundary tests; V1–V6 only.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java backend/src/main/resources backend/src/test/java
git commit -m "feat: add assessment domain schema and state policies"
```

---

## Task 3: Mock OJ、Mock AI 与 Mock 人工评分

**Files:**
- Create: `assessment/adapter/OjAdapter.java`
- Create: `assessment/adapter/MockOjAdapter.java`
- Create: `assessment/adapter/OpenSourceOjAdapter.java` placeholder
- Create: `assessment/service/MockAssessmentScoringService.java`
- Test: `OjAdapterIT`, `AssessmentScoringIT`

**Interfaces:**
- `OjAdapter.judge(OjSubmission): OjJudgeResult`
- `AiEvaluation` fields: score 0..100, reason, model/provider, rubricVersion, status.
- `AssessmentReview` fields: score 0..100, comment, version, review status。

- [ ] **Step 1: Write RED adapter tests**

Cover accepted, wrong answer, time limit, memory limit, runtime error, compilation error, internal error, timeout, malformed response and key/identity leakage.

- [ ] **Step 2: Implement bounded mock adapters**

Mock provider uses deterministic synthetic results; real adapter remains isolated and disabled by default.

- [ ] **Step 3: Run GREEN**

Run: `OjAdapterIT`, `AssessmentScoringIT`, no external network.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: add mock OJ and assessment scoring adapters"
```

---

## Task 4: 考核与题库 API

**Files:**
- Create: `assessment/AssessmentController.java`
- Create: `assessment/AssessmentProblemController.java`
- Test: `AssessmentManagementHttpIT`

**Interfaces:**
- Implement assessment list/create/get/update/publish/close and problem CRUD/retire.
- Admin/leader permissions, draft-only edits, version conflict, Idempotency-Key, audit.

- [ ] **Step 1: Write RED HTTP tests**

Assert role matrix, state transitions, version conflicts, duplicate Idempotency-Key replay, no private test data in responses.

- [ ] **Step 2: Implement service/controller layer**

Use existing `SessionPrincipal`, `MemberScopePolicy`, `IdempotencyService`, `DatabaseSchemaGuard`.

- [ ] **Step 3: Run GREEN**

Run: `AssessmentManagementHttpIT` and contract assertions.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: add assessment and problem APIs"
```

---

## Task 5: 提交、OJ 判定和结果 API

**Files:**
- Create: `assessment/AssessmentSubmissionController.java`
- Create: `assessment/service/AssessmentSubmissionService.java`
- Create: `assessment/service/AssessmentResultService.java`
- Test: `AssessmentSubmissionHttpIT`, `AssessmentResultHttpIT`

**Interfaces:**
- `POST /assessments/{assessmentId}/submissions`
- `GET /assessment-submissions/{submissionId}`
- `GET /assessment-submissions`
- `GET /assessments/{assessmentId}/results`
- Submission stores code securely; result exposes safe OJ summary only.

- [ ] **Step 1: Write RED HTTP tests**

Assert ownership, language restrictions, idempotency, code privacy, OJ status mapping, result aggregation, no point ledger writes.

- [ ] **Step 2: Implement submission/result services**

Invoke `OjAdapter` asynchronously or in bounded worker; preserve submission before execution.

- [ ] **Step 3: Run GREEN**

Run: submission/result IT and privacy checks.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: add assessment submissions and OJ results"
```

---

## Task 6: AI 评估、人工 review 与最终结果

**Files:**
- Create: `assessment/AssessmentEvaluationController.java`
- Create: `assessment/service/AssessmentEvaluationService.java`
- Create: `assessment/service/AssessmentReviewService.java`
- Test: `AssessmentEvaluationHttpIT`, `AssessmentReviewHttpIT`

**Interfaces:**
- `GET /assessment-submissions/{submissionId}/ai-evaluation`
- `POST /assessment-submissions/{submissionId}/reviews`
- `GET /assessment-submissions/{submissionId}/reviews`
- `GET /assessments/{assessmentId}/results`

**Rules:**
- AI evaluation is independent 0–100 score; manual review is 0–100 and overrides AI.
- Human review requires submissionVersion, score, comment, reviewVersion and Idempotency-Key.
- Pending/failed AI produces `finalScore=null`, `scoreSource=PENDING/UNAVAILABLE`.
- No result writes to existing point ledger.

- [ ] **Step 1: Write RED tests**

Assert AI/manual precedence, stale/late results, duplicate review idempotency, review version conflict, pending result states and privacy.

- [ ] **Step 2: Implement evaluation/review/result aggregation**

Persist provider safe metadata only; reject raw provider output in normal responses.

- [ ] **Step 3: Run GREEN**

Run: evaluation/review/result IT and full contract assertions.

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: add assessment evaluation reviews and results"
```

---

## Task 7: GitHub 开源 OJ 选型与适配

**Files:**
- Create: `docs/superpowers/reports/2026-10-12-open-source-oj-selection.md`
- Create: `assessment/adapter/<selected-oj>/...`
- Test: `OpenSourceOjAdapterIT`

**Interfaces:**
- Produces: concrete adapter implementing `OjAdapter`.
- Deployment: Docker/local service configuration, no frontend dependency.

- [ ] **Step 1: Compare GitHub candidates**

Evaluate license, maintenance, language support, sandbox, Docker/API, test-data model, Java integration and security.

- [ ] **Step 2: Implement selected adapter with fake contract tests**

No real credentials; use local fake service in tests.

- [ ] **Step 3: Run GREEN**

Run adapter IT and verify no external network/secret leakage.

- [ ] **Step 4: Commit**

```bash
git add docs backend/src/main/java backend/src/test/java
git commit -m "feat: integrate selected open-source OJ adapter"
```

---

## Task 8: 前端契约联调、全量验证和交付

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `backend/README.md`
- Create: `docs/superpowers/reports/2026-10-12-oj-assessment-system-verification.md`

- [ ] **Step 1: 前端同步契约**

为前端提供 planned DTO、Mock 状态、错误码和角色矩阵；前端先完成页面和 Mock，不等待 OJ 选型。

- [ ] **Step 2: 全量验证**

Run `clean verify`, contract tests, OpenAPI scan, dependency tree, secret scan and Knife4j browser smoke.

- [ ] **Step 3: 独立审查**

Review full branch for OJ sandbox, code privacy, state transitions, API contract and scoring precedence; fix Critical/Important.

- [ ] **Step 4: Commit and push**

Commit verification report and push only `backend`.

---

## Acceptance Matrix

| Requirement | Evidence |
| --- | --- |
| OJ 可替换 | `OjAdapter` and adapter contract tests |
| 学生代码安全 | privacy tests and secret scans |
| OJ verdict | adapter matrix tests |
| AI scoring | independent evaluation tests |
| Human review precedence | review/evaluation tests |
| Independent result | no point_ledger writes |
| Frontend parallel | planned OpenAPI DTOs and Mock workflow |
| Role permissions | permission integration tests |
| Real OJ integration | selected adapter report and local fake tests |
