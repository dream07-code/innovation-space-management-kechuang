# 混合试卷考核（客观题）设计

日期：2026-10-24
版本：v1.0-draft
状态：等待规格确认
前置：`main@4ff5d98`；现有 OJ 考核系统（V6–V11 迁移、判题/复查/结果链路）

## 1. 目标与背景

现有考核只支持程序设计题（OJ 判题）。本设计在同一考核体系内引入**客观题**，
让一场考核成为一张混合试卷：

1. 题型：**单选、多选、判断、填空、程序设计**，同场混排、统一总分。
2. 客观题交卷后自动判分；考试中学生只作答、不显示对错。
3. 客观题支持 **Excel 批量导入**（模板下载、逐行报错、坏行不拖累好行）。
4. 客观题题库**仅方向负责人和学院管理员可见**；学生唯一见到客观题的场合
   是某场已发布考核的试卷内。程序题库保持现状（学生可见、可练习）。
5. 结果页沿用"按学生汇总整场成绩"，口径改为**每题分值累加**。

成功标准：

- 负责人/管理员能建客观题库、Excel 导入、把客观题与程序题混排进考核并逐题定分值。
- 学生在一张试卷里连续作答客观题、跳转代码工作台提交程序题；草稿自动暂存；
  手动交卷或考核截止自动交卷后统一判分。
- 学生的对错明细在考核 `CLOSED` 前不可见（防泄题），负责人/管理员随时可见。
- 现有程序题判题、人工复查、提交列表链路**行为不变**（仅计分口径换算为分值）。

## 2. 已确认的决定（来自需求讨论）

| 决定点 | 结论 |
| --- | --- |
| 题型组织 | 混合试卷，一场考核内单选/多选/判断/填空/程序设计混排 |
| 发起权限 | 沿用现状：`COLLEGE_ADMIN` 全院，`DIRECTION_LEADER` 限所管方向 |
| 客观题库可见性 | 仅负责人/管理员；学生端题库无客观题、无入口、无 API |
| 判分时机 | 交卷统一判；考试中无对错反馈 |
| 计分模型 | 挂题时逐题定分值（整数 1–100），总分 = 各题得分之和 |
| 多选判分 | 全对得满分；错选或漏选一律 0 分 |
| 填空判分 | 支持多个可接受答案（`\|` 分隔），忽略首尾空格，区分大小写 |
| 程序题计分 | 得分 = 分值 × 通过测试点比例；人工复查可覆盖 |
| 答题卡 | 客观题草稿自动暂存；手动交卷 + 截止自动交卷；交卷后锁定 |
| 批量导入 | 客观题 Excel（前端解析、逐行创建，沿用学生导入模式）；程序题 JSON 导入不动 |

## 3. 非目标与反目标

- 不把客观题放进学生练习题库（客观题是考试材料，学生不可练习）。
- 不做部分给分（多选漏选给一半）、不做填空多空、不做题组/共用材料题。
- 不引入 AI 评分或人工批阅到客观题（答案客观，自动判分即终判）。
- 考核结果仍为独立成绩单：不写日报/周报积分流水，不影响排行榜与淘汰状态。
- 不改动 OJ 判题适配层（judge0/local/mock）。

## 4. 方案对比（已选定 B）

| 方案 | 思路 | 取舍 |
| --- | --- | --- |
| A. 扩展 `assessment_problem` | 加题型列与客观题列 | 改动最小，但一半列对客观题无意义；学生端题库靠"过滤题型"保密，一个疏忽就漏考题。弃 |
| **B. 客观题独立成表 + 挂题条目泛化（选定）** | 新表 `quiz_question` + 独立 API；挂题泛化为"考核条目" | 保密靠权限结构而非过滤；程序题链路零改动；挂题与汇总稍复杂，可控 |
| C. 全面重构统一条目模型 | 程序题也搬进条目体系 | 模型最优雅，但牵动判题/复查/结果全链路，风险大收益小。弃 |

## 5. 数据模型（迁移 V12）

### 5.1 `quiz_question` 客观题表

| 列 | 说明 |
| --- | --- |
| `id` BIGINT PK AUTO_INCREMENT | |
| `type` VARCHAR(16) CHECK IN `SINGLE_CHOICE/MULTI_CHOICE/TRUE_FALSE/FILL_BLANK` | 题型 |
| `stem` TEXT NOT NULL | 题干（支持纯文本与换行） |
| `options` JSON NULL | 选择题选项数组（如 `["选项A文本",...]`）；判断/填空为 NULL |
| `answer` JSON NOT NULL | 单选：`"A"`；多选：`["A","B","D"]`；判断：`true/false`；填空：`["ans1","ans2"]` 备选答案 |
| `analysis` VARCHAR(1000) NULL | 解析（判分后学生端展示用） |
| `tags` JSON NOT NULL | 标签（默认 `[]`） |
| `status` VARCHAR(16) CHECK IN `DRAFT/PUBLISHED/RETIRED` | 与程序题一致 |
| `created_by/created_at/updated_at` | 审计字段，FK `app_user` |

- 选项以字母序（A、B、C…）对应数组下标；单选/多选最多 6 个选项、至少 2 个。
- 判断题不存 `options`，前端固定渲染"正确/错误"两个选项。
- 全院共享题库，不带方向维度（与 `assessment_problem` 一致）。
- 客观题不带难度字段（构建者决定：如列表需要可后续加）。

### 5.2 `assessment_item` 考核条目（替代 `assessment_problem_link`）

| 列 | 说明 |
| --- | --- |
| `assessment_id` + `position` | 联合主键，`UNIQUE(assessment_id, position)` 全考核内统一排序 |
| `item_type` VARCHAR(8) CHECK IN `PROGRAM/QUIZ` | |
| `problem_id` BIGINT NULL FK `assessment_problem` | `item_type=PROGRAM` 时非空 |
| `question_id` BIGINT NULL FK `quiz_question` | `item_type=QUIZ` 时非空 |
| `score` INT NOT NULL CHECK BETWEEN 1 AND 100 | 该题分值 |

- CHECK：`problem_id` 与 `question_id` 恰好一个非空。
- 不允许重复挂题：`UNIQUE(assessment_id, problem_id)` 与
  `UNIQUE(assessment_id, question_id)`（沿用旧表"一场考核一题至多一次"的语义；
  否则按 `problem_id` 关联的提交/判分/汇总会出现多分值歧义）。
- 迁移：`assessment_problem_link` 全量搬入，`score` 默认 100（旧口径"每题满分
  100"不变，历史总分不变），随后旧表删除。
- 分值限 1–100 是业务上限（与人工评分 0–100 百分制对齐）；注意
  `assessment_result.final_score` 本身并无 CHECK 约束，无需改动。

### 5.3 `assessment_answer_sheet` 答题卡

| 列 | 说明 |
| --- | --- |
| `id` BIGINT PK | |
| `assessment_id` + `member_id` | `UNIQUE(assessment_id, member_id)`，一人一卷 |
| `answers` JSON NULL | 草稿答案：按 `position` 到答案值的映射 |
| `status` VARCHAR(16) CHECK IN `DRAFT/SUBMITTED` | 交卷后锁定 |
| `submitted_at` DATETIME(6) NULL | 手动交卷时间；截止自动交卷由批次任务补 `SUBMITTED` |
| `total_score` INT NULL / `graded_at` | 判分结果汇总，判分完成时写入 |
| `created_at/updated_at` | |

### 5.4 `assessment_answer_sheet_item` 判分明细

| 列 | 说明 |
| --- | --- |
| `sheet_id` FK + `position` | 联合主键，对应 `assessment_item.position` |
| `question_id` BIGINT FK `quiz_question` | |
| `answer` JSON | 学生最终答案 |
| `correct` TINYINT | 是否得分 |
| `awarded` INT NOT NULL | 得分（0 或分值） |

- 交卷判分时逐题展开落库；结果页对错明细、错题解析从这里读。
- 无明细的客观题条目（未答）不落行，汇总按 0 分处理。

### 5.5 既有表的口径变化

- `assessment_result.final_score`（程序题）：由"通过比例百分数"改为
  **"分值口径得分 = item.score × passed/total"**；`assessment_review` 人工评分
  仍为 0–100，换算 `item.score × review.score / 100` 后写入 final_score。
- 两种换算均**四舍五入取整**（`Math.round`，与现有
  `AssessmentEvaluationServiceImpl.autoScore` 一致；迁移回填 `score=100` 时
  换算结果与旧百分数完全相等，保证"旧考核总分不变"可测）。
- 按学生汇总（`member-scores`）口径精确定义：
  - `itemCount`（原 `problemCount`）：考核条目总数（含客观题）。
  - `totalScore`：程序题各题**最后一次提交**的 final_score 之和 + 答题卡
    total_score。
  - `answeredCount`：有作答痕迹的条目数 = 有提交记录的程序题数（按题去重）+
    答题卡中非空作答的客观题数。
  - `completedCount`：判分就绪的条目数 = 最后一次提交 `result_status=COMPLETE`
    的程序题数 + 已交卷答题卡中已判分的客观题条目数（不论对错）。
  - `maxScore`：`SUM(assessment_item.score)`（分值口径满分，替代旧的
    `题数×100`）。
  - `scoreRate`（替代 `averageScore`）：该生得分率 = round(totalScore×100/
    maxScore)，前端列名改为"得分率"（每题平均分在分值制下已无直观意义）。

## 6. API 设计

沿用 `ApiResponse` 包装、Idempotency-Key（写操作）、service 层 `SessionPrincipal`
权限检查与审计的现有模式。

### 6.1 客观题库（仅负责人/管理员，学生 404）

- `GET /api/v1/quiz-questions`：分页列表（type/status/关键字过滤）。
- `POST /api/v1/quiz-questions`：创建（逐行导入也走它，带 Idempotency-Key）。
- `GET /api/v1/quiz-questions/{id}` / `PATCH .../{id}`：详情、编辑。
- `POST /api/v1/quiz-questions/{id}/retire`：停用（沿用程序题模式）。
- **生命周期与程序题完全对齐**：考核发布时，所挂客观题 `DRAFT→PUBLISHED`；
  仅 `DRAFT` 题可编辑（PATCH），`PUBLISHED`/`RETIRED` 不可改答案/选项/题干。
  判分始终读题库现行行，因此发布后题目不可变，杜绝"交卷后改答案翻盘"；
  `RETIRED` 只是退出题库选择，已被考核引用的条目判分不受影响。
- 权限：`COLLEGE_ADMIN` 全部；`DIRECTION_LEADER` 全部（题库全院共享，与
  程序题库一致）。

### 6.2 考核条目

- `POST /api/v1/assessments`、`PATCH /api/v1/assessments/{id}` 的载荷从
  `problemIds[]` 改为 `items[{type, refId, score}]`；服务端校验顺序、分值、
  引用存在且非 RETIRED。
- `PUBLISHED` 后条目不可删除、分值不可改（沿用现有发布锁逻辑，扩展到条目）。

### 6.3 学生试卷与答题卡

- `GET /api/v1/assessments/{id}/paper`：PUBLISHED 且本方向才可读。返回条目
  列表：客观题含题干/选项（**绝不含 answer/analysis**），程序题含元信息与
  已有提交状态摘要；附带本人答题卡草稿。
- `PUT /api/v1/assessments/{id}/answer-sheet`：草稿暂存（整体覆盖 answers JSON，
  仅 `DRAFT` 状态、窗口内可写）。
- `POST /api/v1/assessments/{id}/answer-sheet/submit`（Idempotency-Key）：交卷
  并同步判分；考核截止后接口拒绝。
- 截止自动交卷：新增 `@Scheduled(fixedDelay)` 扫描任务（参照现有
  `MembershipRemovalScheduler`/`ScoringWorkerScheduler` 的调度模式，而非
  `AssessmentJudgeWorker` 的入队驱动模式），把窗口已结束且仍为 `DRAFT` 的
  答题卡补交卷并判分。
- **提前关闭（`PUBLISHED→CLOSED` 手动操作）等同"提前截止"**：关闭命令同步把
  该考核所有 `DRAFT` 答题卡视作已交卷并判分（与截止自动交卷同一套逻辑），
  避免在批学生答题中途被清零、也不留下悬空草稿。

### 6.4 结果

- `GET /api/v1/assessments/{id}/member-scores`：口径见 5.5。
- `GET /api/v1/assessments/{id}/answer-sheets`（负责人/管理员）：逐生答题卡
  明细；学生本人经成绩单入口可见自己的明细，但**考核 `CLOSED` 前对错、得分、
  解析一律不返回**（草稿/已交状态可见）。

## 7. Excel 导入（前端，负责人/管理员）

沿用学生导入页模式：`xlsx` 库解析、模板下载、逐行调用创建 API、行级错误展示、
坏行不拖累好行。

模板列（一个 sheet 混排四种题型）：

| 题型 | 题干 | 选项A–F | 答案 | 解析 | 标签 |
| --- | --- | --- | --- | --- | --- |

- 题型填中文：单选/多选/判断/填空。
- 单选答案填 `A`–`F`；多选填如 `ABD`；判断填 `对/错`；填空多个备选答案用 `|` 分隔。
- 选项列填空即少一个选项；单选/多选至少 2 个、至多 6 个。
- 标签列用 `、` 或 `,` 分隔，可空。

## 8. 前端页面

| 页面 | 说明 |
| --- | --- |
| `QuizQuestionListView` / `QuizQuestionEditView`（新） | 客观题库管理：列表过滤、新建/编辑四题型、Excel 导入入口（负责人/管理员路由） |
| `AssessmentEditView`（改） | 挂题改为"条目"编辑：程序题与客观题混排、拖拽/上下移排序、逐题分值输入、分值合计展示 |
| `ExamPaperView`（新，学生） | 试卷页：客观题连续作答（单选/多选/判断/填空控件）、草稿自动暂存、程序题条目跳转 `CodeSubmitView`、手动交卷（确认框） |
| `CodeSubmitView`（基本不动） | 程序题工作台保持现状 |
| `AssessmentResultsView`（改） | 分值口径汇总；负责人/管理员可下钻某学生的答题卡对错明细 |
| `AssessmentDetailView`（改） | 展示试卷结构（题型图标、分值） |

## 9. 安全与权限要点

- `quiz-questions` 相关路由/API 对 `STUDENT` 一律 404（与现有不可见资源口径一致）。
- `paper` 响应永远不含 `answer`/`analysis`；`analysis` 仅在考核 `CLOSED` 后
  随本人明细下发。
- 答题卡读写校验：本人、窗口内、状态机（`DRAFT→SUBMITTED` 单向）。
- 判分在服务端内存完成后一次性落库，不提供"逐题试判"接口。
- 客观题写操作写 `assessment_audit`（沿用现有审计动作格式）。

## 10. 质量要求（沿用项目测试文化）

- 后端：service 层单测覆盖判分规则（多选全对/错选/漏选、填空多答案与空格、
  程序题分值换算、人工覆盖）、答题卡状态机、权限（学生 404、窗口外拒绝）、
  迁移后旧数据口径（旧考核总分不变）。
- 前端：四题型作答控件、草稿暂存、交卷流程、条目编辑与分值合计的组件 spec。
- 手动验收路径：导 Excel 题库 → 建混合考核 → 学生答题交卷 → 截止自动交卷 →
  结果汇总与明细下钻。

## 11. 留给构建者的

- Excel 列的精确表头文案与校验错误信息。
- 试卷页组件拆分与窄屏布局（参照 `CodeSubmitView` 的响应式做法）。
- `answers` JSON 的精确键结构（建议 `{"<position>": value}`，value 按题型为
  字母/数组/布尔/字符串）。
- 客观题列表页的过滤交互细节。
- 迁移 SQL 的索引微调（答题卡按考核+成员查询为主路径）。
