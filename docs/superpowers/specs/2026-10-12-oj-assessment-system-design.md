# OJ 考核系统设计

日期：2026-10-12  
版本：v1.0-draft  
状态：等待规格确认  
前置：后端 `backend@74cd50c`，前端可依据本设计预留页面和 Mock

## 1. 目标

建设一个带在线判题能力的学院考核系统：

1. 学院管理员或方向负责人创建考核和编程题目。
2. 学生在浏览器中阅读题目并提交代码。
3. GitHub 上的成熟开源 OJ 负责编译、沙箱运行、测试用例判定和资源限制。
4. 大模型根据题目、代码、OJ 结果和评分规则给出独立质量评分。
5. 方向负责人可人工批阅同一提交；人工评分优先于 AI 评分。
6. 考核结果作为独立成绩单展示，不计入现有科创空间日报/周报积分、积分流水或排行榜。

成功标准：

- OJ 产品通过适配层接入，不锁定某一开源项目的私有 API。
- OJ 技术判定、AI 评分和人工评分分别可追溯。
- 前后端可先根据同一 OpenAPI 契约并行开发，OJ 未接入前可使用 Mock。
- 提交代码、测试结果、评分和人工意见遵循现有角色权限和数据最小化原则。
- 不把第三方 OJ 的账号、密钥、原始异常或任意执行参数暴露给浏览器。

## 2. 角色与权限

| 角色 | 权限 |
| --- | --- |
| 学院管理员 | 创建/配置/发布全院考核，管理题库，查看全部结果，发起 AI 评分，执行人工批阅 |
| 方向负责人 | 创建和管理获授权方向的考核，查看本方向题目/提交/结果，执行人工批阅 |
| 学生 | 查看已发布且属于本人范围的考核和题目，提交代码，查看本人过程状态和结果 |

- 学生不能自行注册，沿用现有身份和会话体系。
- 学生不能查看他人代码、测试详情、评分草稿或完整测试数据。
- 方向负责人不能跨方向读取提交或批阅。
- OJ 执行是内部能力，不提供浏览器调用任意代码、脚本或 URL 的接口。

## 3. 核心流程

```text
创建考核/题目 -> 发布 -> 学生提交代码 -> OJ编译运行判定
                                      -> AI质量评分
                                      -> 负责人人工批阅（可选）
                                      -> 汇总独立考核结果
```

### 3.1 考核

- 状态：`DRAFT -> PUBLISHED -> CLOSED -> ARCHIVED`。
- 考核包含标题、说明、方向范围、开始/截止时间、题目集合、允许语言和评分策略。
- `DRAFT` 可修改题目和配置；`PUBLISHED` 后不能删除题目，只能有限修改并记录版本。
- `CLOSED` 后不再接收新提交，已有结果仍可查看和批阅。
- 发布、关闭和归档均要求 Idempotency-Key 并写审计。

### 3.2 题目

- 题目包含标题、描述、输入/输出说明、示例、难度、标签、时间/内存限制、允许语言和测试用例标识。
- 公开题目描述与私有测试数据分离。
- 私有测试数据只保存在受控 OJ/存储中，不通过普通题目接口返回。
- 题目支持 `DRAFT/PUBLISHED/RETIRED` 状态；已用于已发布考核的题目不能物理删除。

### 3.3 学生提交

- 每次提交记录 `assessmentId/problemId/memberId/language/codeHash` 和安全的代码存储引用。
- 代码正文可保存在受控存储或数据库 JSON；日志、审计和普通响应不得输出完整代码。
- 提交状态：`RECEIVED -> JUDGING -> JUDGED/FAILED/NEEDS_REVIEW`。
- 同一题目允许多次提交的策略由考核配置决定；首期默认允许多次，结果取考核规则指定的最佳/最新提交。
- 提交接口要求 Idempotency-Key；重复请求回放第一次结果。

### 3.4 OJ 判定

通过 `OjAdapter` 隔离开源 OJ：

```java
interface OjAdapter {
  OjJudgeResult judge(OjSubmission submission);
}
```

适配器负责：

- 编译指定语言。
- 在沙箱中运行不可信代码。
- 应用时间、内存、进程和输出限制。
- 执行公开/私有测试用例。
- 返回标准结果：`ACCEPTED/WRONG_ANSWER/TIME_LIMIT_EXCEEDED/MEMORY_LIMIT_EXCEEDED/RUNTIME_ERROR/COMPILATION_ERROR/INTERNAL_ERROR`。
- 返回通过测试数、总测试数、时间、内存和安全的错误分类。

OJ 产品选择标准：

- GitHub 开源许可证允许本项目使用和部署。
- 活跃维护，文档完整，支持 Java/常见算法语言。
- 提供 Docker/本地部署和 API 或可可靠集成的数据接口。
- 具备编译/运行沙箱、资源限制和测试数据管理。
- 不要求把学院身份数据同步给第三方公网服务。
- 允许通过适配层替换，不把前端绑定到 OJ 自有页面或字段。

具体开源 OJ 产品在实施计划阶段通过 GitHub 候选评估后确定。

### 3.5 AI 评分

- 触发方式：OJ 判定完成后自动排队，或管理员显式重新发起。
- 输入：题目描述、允许语言、学生代码、OJ 摘要、评分 rubric；默认不传姓名、学号和联系方式。
- 输出：`score`、简短 `reason`、模型标识、rubricVersion 和安全状态。
- 评分范围默认 0–100；具体 rubric 可按考核配置。
- AI 评分是独立质量评分，不修改 OJ 判定结果。
- 保存 `model/provider` 标识，不保存 API Key；原始模型响应按最小权限保存。
- 超时、非法响应和网络失败进入 `AI_FAILED/NEEDS_REVIEW`，不伪造0分。

### 3.6 人工批阅

- 方向负责人或管理员为指定提交创建 review。
- 请求包含 `submissionVersion`、`score(0..100)`、`comment` 和 `reviewVersion`，要求 Idempotency-Key。
- 同一提交可有多个历史 review，但只有一个 `ACTIVE` 最终人工结果。
- 人工结果一旦确认优先于 AI 评分；迟到 AI 评分不得覆盖人工结果。
- 人工 review 的审计记录操作者、提交、分数版本和时间。

### 3.7 独立考核结果

结果字段：

- `technicalVerdict`
- `passedTestCases/totalTestCases`
- `timeMs/memoryKb`
- `aiScore/aiReason/aiStatus`
- `humanScore/humanComment/humanStatus`
- `finalScore`
- `scoreSource`：`HUMAN/AI/PENDING/UNAVAILABLE`
- `resultStatus`：`PENDING/COMPLETE/NEEDS_REVIEW`
- `publishedAt`

汇总规则：

1. 已确认人工评分优先。
2. 无人工评分时使用有效 AI 评分。
3. 两者都没有时 `finalScore=null`、`scoreSource=PENDING`。
4. OJ 技术判定独立展示，不能因 AI/人工评分覆盖。
5. 考核结果不写入 `point_ledger`，不改变日报/周报积分和排行榜。

## 4. API 契约预留

以下路径先以 `planned` 状态写入 `docs/api/openapi.yaml`，供前后端并行开发；OJ 产品和内部实现不进入前端契约。

### 4.1 考核

- `GET /api/v1/assessments`
- `POST /api/v1/assessments`
- `GET /api/v1/assessments/{assessmentId}`
- `PATCH /api/v1/assessments/{assessmentId}`
- `POST /api/v1/assessments/{assessmentId}/publish`
- `POST /api/v1/assessments/{assessmentId}/close`

### 4.2 题目

- `GET /api/v1/assessment-problems`
- `POST /api/v1/assessment-problems`
- `GET /api/v1/assessment-problems/{problemId}`
- `PATCH /api/v1/assessment-problems/{problemId}`
- `POST /api/v1/assessment-problems/{problemId}/retire`

### 4.3 提交和 OJ 状态

- `POST /api/v1/assessments/{assessmentId}/submissions`
- `GET /api/v1/assessment-submissions/{submissionId}`
- `GET /api/v1/assessment-submissions`

### 4.4 评分和结果

- `GET /api/v1/assessment-submissions/{submissionId}/ai-evaluation`
- `POST /api/v1/assessment-submissions/{submissionId}/reviews`
- `GET /api/v1/assessment-submissions/{submissionId}/reviews`
- `GET /api/v1/assessments/{assessmentId}/results`

### 4.5 契约约束

- ID 一律以字符串传输。
- 所有创建/发布/关闭/提交/review 写操作使用 Idempotency-Key。
- 草稿、私有测试数据、完整代码和 API Key 不进入普通列表响应。
- 错误沿用现有 `code/message/data` 结构和 400/401/403/404/409/503 语义。
- `x-implementation-status` 初始为 `planned`，只有真实注册并通过契约测试后才改为 `implemented`。

## 5. 数据对象

- `Assessment`
- `AssessmentProblem`
- `AssessmentSubmission`
- `OjJudgeResult`
- `AiEvaluation`
- `AssessmentReview`
- `AssessmentResult`
- `AssessmentAudit`

关系约束：

- Assessment 与 Problem 多对多。
- Submission 必须关联 Assessment、Problem、Member。
- OjJudgeResult 与 Submission 一对一。
- AiEvaluation 与 Submission 一对一版本历史。
- AssessmentReview 与 Submission 一对多，但只有一个 active。
- AssessmentResult 由 submission 状态、OJ、AI 和人工结果派生。

## 6. 安全边界

- 编译和运行只在受控 OJ 沙箱内执行。
- 不允许请求体传入 shell、任意命令、任意 URL、测试脚本或运行参数。
- 代码、测试数据和 OJ 错误分类按角色最小可见。
- API Key、数据库密码、OJ 管理凭据只在服务端密钥配置中注入。
- OJ 适配器超时、断路、失败和恢复均记录安全审计。
- 多实例部署、OJ 队列隔离、测试数据备份和代码保留期限属于部署阶段确认事项。

## 7. 前后端并行开发

前端可先开发：

1. 考核列表、详情、创建/编辑/发布/关闭。
2. 题库列表、题目详情和代码编辑器。
3. 提交状态、OJ 判定结果和 AI/人工评分展示。
4. 负责人批阅页面和独立结果页。
5. Mock 数据层按上述接口和 schema 对齐。

后端在 OJ 选型完成前：

1. 先发布 `planned` OpenAPI 路径和 DTO。
2. 实现接口校验、权限、状态机、Mock OJ adapter 和 Mock AI/人工评分。
3. 再接入 GitHub 开源 OJ 和真实沙箱。
4. 最后启用真实 AI 评分和人工 review。

## 8. 非目标

- 不实现 OJ 自身的用户注册、社区、竞赛社交和题解论坛。
- 不把考核分数计入日报/周报积分或现有排行榜。
- 不实现月度评定/淘汰。
- 不允许学生上传任意可执行文件。
- 不在浏览器端执行代码。
- 不承诺任何特定 GitHub OJ 产品，选型需单独评估许可证、沙箱和部署条件。

## 9. 待确认事项

1. 考核允许的编程语言集合。
2. 题目来源：自建题库、导入公开题库或两者混合。
3. AI 评分 rubric 和0–100分权重。
4. 人工评分是否必须二人复核。
5. 代码和测试数据保留期限。
6. OJ 部署方式：本地 Docker、学院服务器或隔离测试集群。
7. 考核结果是否需要导出成绩单/CSV。
8. 学生提交次数、迟交和补交策略。
