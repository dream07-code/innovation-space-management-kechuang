# OJ Judge0 测试点与做题工作台设计

> **For agentic workers:** This spec is the approved design for JSON import, private test-case storage, split-screen solving, Judge0 execution, and per-test-case result summaries.

**Goal:** 让题库具备可批量维护的隐藏测试点，并通过 Judge0 真实判题；学生提交后能看到安全的逐测试点结果，同时不泄露隐藏输入、期望输出或原始判题日志。

**Architecture:** 题目公开示例与隐藏测试点分开存储；后端负责权限、校验、私有测试点加载和 Judge0 调用；前端负责 JSON 导入、双栏做题和结果轮询。Judge0 仅在服务端网络访问，浏览器永远不接触 Judge0 地址、Token、原始 stdout/stderr 或隐藏测试点。

**Tech Stack:** Java 21、Spring Boot、MyBatis-Plus、MySQL 8.0、Vue 3、TypeScript、Judge0 Community Edition。

## Global Constraints

- 学生不能读取隐藏测试点、Judge0 API Token、原始 stdout/stderr 或代码执行日志。
- 管理员和方向负责人可以维护题目与测试点；学生只能提交和查看自己的安全结果。
- JSON 导入是原子的：格式、权限、重复题目或任一题目校验失败时，不写入任何题目。
- 所有外部写操作继续使用 `Idempotency-Key`。
- 隐藏测试点数量为 1–100/题；单个输入和期望输出不超过 1,000,000 字符。
- 批量导入请求最大 5 MB、最多 100 道题。
- 公开 `examples` 继续出现在普通题目响应中；私有测试点不出现在普通题目响应中。
- Judge0 状态必须映射为稳定的安全 verdict，不能把 provider 原始响应透传给前端。
- Mock OJ 保留为无 Judge0 时的降级模式，但必须使用测试点数量生成逐点摘要。

## Review Focus

- 学生查询题目详情或列表时是否可能读取私有测试点。
- Judge0 返回非法状态、超时、响应过大时是否仍能显示安全结果。
- 批量导入是否可能部分写入、重复创建或泄露文件内容。
- 前端双栏页面在窄屏、长题面、长代码和结果加载时是否可操作。
- 逐测试点结果是否只包含安全字段。

## Tasks

### Task 1: 私有测试点模型与题目 CRUD

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__create_assessment_problem_test_cases.sql`
- Modify: `backend/src/main/java/.../AssessmentProblemServiceImpl.java`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Modify: `docs/api/openapi.yaml`
- Modify: `frontend/src/api/modules/oj.ts`
- Modify: `frontend/src/views/oj/ProblemEditView.vue`

**Requirements:**
- 新表存储 `problem_id`、`position`、`input`、`expected_output`，并按题目/序号唯一。
- 创建题目请求支持 `testCases`；更新题目请求支持替换 `testCases`。
- 普通题目响应只返回 `examples`，不返回 `testCases`。
- 前端编辑页增加“隐藏测试点”编辑区，支持添加、删除、排序。
- 服务端对数量、长度、空输入、重复序号和角色进行校验。

### Task 2: JSON 批量导入

**Files:**
- Modify: `backend/src/main/java/.../AssessmentProblemServiceImpl.java`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Modify: `docs/api/openapi.yaml`
- Modify: `frontend/src/api/modules/oj.ts`
- Modify: `frontend/src/views/oj/ProblemListView.vue`
- Create: `frontend/src/utils/ojImport.ts`

**Requirements:**
- `POST /api/v1/assessment-problems/import` 接收 `{ problems: [...] }` JSON。
- 每道题包含公开题面、`examples` 和至少一个私有 `testCases`。
- 逐题校验并在一个事务中创建全部题目。
- 返回创建数量、题目 ID、逐题错误；错误不返回文件内容。
- 前端读取本地 JSON 文件后预览校验，再发送导入请求。
- 导入必须是管理员/负责人权限，并带幂等键。

### Task 3: Judge0 逐测试点判题

**Files:**
- Modify: `backend/src/main/java/.../OjJudgeResult.java`
- Modify: `backend/src/main/java/.../AssessmentJudgeWorker.java`
- Modify: `backend/src/main/java/.../Judge0OjAdapter.java`
- Create: `backend/src/main/resources/db/migration/V9__create_oj_test_case_results.sql`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Modify: `docs/api/openapi.yaml`

**Requirements:**
- 判题时按 `problem_id` 加载隐藏测试点，构造带测试点的 `OjSubmission`。
- Judge0 对每个测试点返回状态、耗时、内存和安全错误摘要。
- 每个测试点结果保存为 `ACCEPTED` 或失败 verdict，不保存隐藏输入、期望输出或原始输出。
- `oj_judge_result` 保留总体结果和 `passed/total`。
- `AssessmentSubmission` 响应新增安全的 `testCaseResults` 数组。
- provider 不可用时整体返回 `INTERNAL_ERROR`，逐点结果为不可用摘要。

### Task 4: 左右双栏做题页与结果页

**Files:**
- Modify: `frontend/src/views/oj/CodeSubmitView.vue`
- Modify: `frontend/src/views/oj/SubmissionDetailView.vue`
- Modify: `frontend/src/api/modules/oj.ts`
- Modify: `frontend/src/api/types.ts`

**Requirements:**
- 桌面端左题面、右编辑器；结果出现后右侧切换为结果区或下方结果区。
- 窄屏使用“题目 / 代码 / 结果”标签切换。
- 保留语言、提交、返回和状态轮询。
- 结果页显示总体 verdict、`passed/total`、逐测试点状态、耗时、内存和安全错误。
- 隐藏测试点不显示输入、期望输出、原始 stdout/stderr。

### Task 5: Mock 降级与验证

**Files:**
- Modify: `backend/src/main/java/.../MockOjAdapter.java`
- Modify: `backend/src/test/java/.../AssessmentSubmissionHttpIT.java`
- Modify: `frontend/src/views/oj/ProblemEditView.vue`
- Modify: `frontend/src/utils/ojImport.ts`

**Requirements:**
- 无 Judge0 时 Mock 根据提交代码标记和测试点数量生成总体与逐点安全结果。
- 自动化测试覆盖题目创建、JSON 导入、学生不可见测试点、Judge0 状态映射和前端结果字段。
- 后端执行完整 `clean verify`，前端执行 test/type-check/lint/build。
- 本地测试环境更新后，学生可以进入双栏做题页并提交。

## Acceptance

- 管理员可以一次导入多道带隐藏测试点的题目。
- 学生题库和题目详情看不到隐藏测试点。
- 提交代码后结果页显示总体结果和每个测试点的安全状态。
- Judge0 配置正确时真实判题；未配置时 Mock 降级并明确标注测试环境。
- 所有已有认证、权限、日报、积分和 OJ 回归测试保持通过。
