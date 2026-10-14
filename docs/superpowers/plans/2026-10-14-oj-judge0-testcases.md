# OJ Judge0 测试点与做题工作台 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 增加正式考核隐藏测试点、题库练习测试点、JSON 批量导入、双栏做题工作台和 Judge0 逐测试点判题结果，同时保证正式考核测试点不泄露。

**Architecture:** 后端新增两套独立测试点存储和权限过滤；正式考核提交由 Judge0 逐测试点执行，题库练习使用独立练习测试点。前端增加 JSON 导入、双栏题目/代码/结果布局和安全结果展示。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、MySQL 8.0、Vue 3、TypeScript、Judge0 Community Edition。

**Spec:** `/home/dream/Projects/innovation-space-management/docs/superpowers/specs/2026-10-14-oj-judge0-testcases-design.md`

## Global Constraints

- 正式考核隐藏测试点不得出现在普通题目响应、学生题库响应或正式考核结果响应中。
- 题库练习测试点只能在练习模式中显示，不能用于正式考核判题。
- JSON 批量导入必须原子执行，任何题目校验失败时不得写入部分题目。
- 每道题正式考核测试点 1–100 个，练习测试点 0–100 个。
- Judge0 API Token、原始 stdout/stderr 和正式考核输入/期望输出不得进入前端或普通审计响应。
- 写操作必须使用 `Idempotency-Key`，并保持现有 HTTP 状态码、分页和权限语义。
- Mock OJ 必须能为正式考核和练习分别生成安全的逐测试点结果。

## Review Focus

- 两套测试点是否可能在查询、导入、判题或结果响应中串用。
- 正式考核学生是否能通过普通题目接口、练习接口或结果接口读取隐藏测试点。
- JSON 导入是否存在部分写入、重复题目、超大文件或逐题错误泄露文件内容。
- 双栏做题页在桌面、窄屏、长代码和轮询中的可用性。
- Judge0 非法状态、超时、空测试点和 provider 故障是否仍返回安全结果。

## Task 1: 测试点数据库与题目 CRUD

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__create_oj_test_cases.sql`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentProblemServiceImpl.java`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Modify: `docs/api/openapi.yaml`
- Modify: `frontend/src/api/modules/oj.ts`
- Modify: `frontend/src/views/oj/ProblemEditView.vue`

**Interfaces:**
- Consumes: existing `assessment_problem` schema and staff-only problem APIs.
- Produces: create/update requests containing `assessmentTestCases` and `practiceTestCases`; normal `AssessmentProblem` response remains without test cases.

**Steps:**
- [ ] **Step 1:** 创建正式考核测试点和练习测试点表，分别包含 `problem_id`、`position`、`input`、`expected_output`，并建立唯一键与外键。
- [ ] **Step 2:** 在题目创建、更新和 JSON 验证中分别校验两套测试点的数量、长度、空值和重复序号。
- [ ] **Step 3:** 确保普通题目 `get/list/response` 不返回两套测试点，新增仅 staff 可用的测试点读取接口。
- [ ] **Step 4:** 前端题目编辑页增加两套测试点编辑区，提交时分别发送 `assessmentTestCases` 和 `practiceTestCases`。
- [ ] **Step 5:** 运行题目 CRUD 与权限测试，确认学生读取不到正式考核测试点，确认练习测试点不会混入正式考核请求。

## Task 2: JSON 批量导入

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentProblemServiceImpl.java`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Modify: `docs/api/openapi.yaml`
- Modify: `frontend/src/api/modules/oj.ts`
- Modify: `frontend/src/views/oj/ProblemListView.vue`
- Create: `frontend/src/utils/ojImport.ts`

**Interfaces:**
- Consumes: Task 1 的两套测试点校验与题目创建接口。
- Produces: `POST /api/v1/assessment-problems/import` 和前端 JSON 文件预览/导入流程。

**Steps:**
- [ ] **Step 1:** 定义导入 JSON schema：最多 100 道题，每题包含公开题面、`examples`、`assessmentTestCases` 和可选 `practiceTestCases`。
- [ ] **Step 2:** 后端读取请求 JSON，执行整体大小、字段、重复题目、测试点和角色校验。
- [ ] **Step 3:** 在单个事务中创建全部题目；任一题目失败则回滚并返回逐题错误。
- [ ] **Step 4:** 返回创建题目 ID、数量和不含原始文件内容的安全错误摘要。
- [ ] **Step 5:** 前端实现本地 JSON 文件读取、预览、校验和提交，失败时不写入 UI 状态。
- [ ] **Step 6:** 增加成功、原子失败、重复题目、超大导入和权限测试。

## Task 3: Judge0 正式考核逐测试点判题

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__create_oj_test_case_results.sql`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/OjJudgeResult.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/service/AssessmentJudgeWorker.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/adapter/judge0/Judge0OjAdapter.java`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Modify: `docs/api/openapi.yaml`

**Interfaces:**
- Consumes: 正式考核隐藏测试点和现有 `OjSubmission` / `OjAdapter`。
- Produces: `oj_judge_result` 总体摘要、`oj_test_case_result` 逐点安全摘要、提交响应中的 `testCaseResults`。

**Steps:**
- [ ] **Step 1:** 为正式考核结果创建逐测试点表，只保存序号、verdict、耗时、内存和安全错误，不保存输入、期望输出或原始输出。
- [ ] **Step 2:** 判题 Worker 按 `problem_id` 加载正式考核测试点并构造 `OjSubmission`。
- [ ] **Step 3:** Judge0 适配器逐测试点提交并映射状态，provider 故障返回整体 `INTERNAL_ERROR` 和安全逐点摘要。
- [ ] **Step 4:** 保存总体 `passed/total` 和逐点结果，确保旧版无逐点结果时仍能返回兼容摘要。
- [ ] **Step 5:** 扩展提交详情/结果响应，只返回安全 `testCaseResults`。
- [ ] **Step 6:** 增加 Judge0 fake contract、空测试点、超时、非法状态和学生响应不泄露测试点的测试。

## Task 4: 题库练习判题与练习结果

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentProblemServiceImpl.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/service/AssessmentJudgeWorker.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/adapter/MockOjAdapter.java`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Modify: `docs/api/openapi.yaml`
- Modify: `frontend/src/api/modules/oj.ts`
- Modify: `frontend/src/views/oj/ProblemDetailView.vue`
- Modify: `frontend/src/views/oj/CodeSubmitView.vue`

**Interfaces:**
- Consumes: Task 1 的独立练习测试点和 Task 3 的安全结果结构。
- Produces: 练习模式练习测试点展示、练习提交和练习结果；不改变正式考核提交语义。

**Steps:**
- [ ] **Step 1:** 增加仅学生可用的练习测试点读取与练习判题请求，使用 `practiceTestCases`，禁止使用 `assessmentTestCases`。
- [ ] **Step 2:** Mock OJ 根据对应测试点集合生成总体和逐点结果；Judge0 模式使用同一流程。
- [ ] **Step 3:** 练习结果可显示输入、期望输出、状态、耗时、内存和安全错误；正式考核结果继续隐藏输入/期望输出。
- [ ] **Step 4:** 前端题库练习页增加“查看练习测试点”和“练习运行结果”，并明确标注与正式考核隔离。
- [ ] **Step 5:** 增加练习模式可见性、正式考核不可见、两套测试点不串用和结果字段测试。

## Task 5: 双栏做题工作台与结果轮询

**Files:**
- Modify: `frontend/src/views/oj/CodeSubmitView.vue`
- Modify: `frontend/src/views/oj/SubmissionDetailView.vue`
- Modify: `frontend/src/api/modules/oj.ts`
- Modify: `frontend/src/api/types.ts`
- Modify: `frontend/src/api/types.gen.ts`

**Interfaces:**
- Consumes: Task 3/4 的提交状态与安全结果 DTO。
- Produces: 桌面左右分栏、窄屏标签切换、提交轮询和逐测试点结果展示。

**Steps:**
- [ ] **Step 1:** 将做题页改为桌面左侧题面、右侧编辑器；结果加载后在右下方或结果标签显示。
- [ ] **Step 2:** 窄屏改为“题目 / 代码 / 结果”标签切换，保留提交和返回操作。
- [ ] **Step 3:** 提交后轮询状态，支持 `RECEIVED`、`JUDGING`、`JUDGED`、`FAILED`、`NEEDS_REVIEW`。
- [ ] **Step 4:** 结果页展示总体 verdict、`passed/total` 和逐测试点状态/耗时/内存/安全错误；按模式决定是否显示输入和期望输出。
- [ ] **Step 5:** 增加组件测试、轮询测试、长代码布局测试和正式/练习结果字段测试。

## Task 6: 本地 Judge0 与最终验证

**Files:**
- Modify: `backend/README.md`
- Modify: `deploy/README.md`
- Modify: `deploy/docker-compose.yml` only if needed for local Judge0 integration.
- Test: local Docker Judge0 or fake HTTP contract.

**Interfaces:**
- Consumes: Tasks 1–5 outputs.
- Produces: 可运行的本地真实/降级判题验证和上线前说明。

**Steps:**
- [ ] **Step 1:** 尝试在本地启动隔离 Judge0；若环境不具备，则使用现有 fake HTTP contract 完成适配层验证，并记录限制。
- [ ] **Step 2:** 配置 `OJ_PROVIDER=judge0` 或 Mock 降级，验证题目创建、导入、正式判题和练习判题。
- [ ] **Step 3:** 验证正式考核响应不含隐藏测试点，练习响应只含练习测试点。
- [ ] **Step 4:** 执行后端 `clean verify` 和前端 `test/type-check/lint/build`。
- [ ] **Step 5:** 更新 README、OpenAPI 和上线前说明，提交最终报告。
