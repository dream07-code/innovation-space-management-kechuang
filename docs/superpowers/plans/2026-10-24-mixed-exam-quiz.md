# 混合试卷考核（客观题）实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在现有 OJ 考核体系中加入单选/多选/判断/填空客观题：独立保密题库、混合挂题定分值、学生试卷作答、交卷统一自动判分、成绩按分值口径汇总。

**Architecture:** 客观题独立成 `quiz_question` 表与仅限负责人/管理员的 API；考核挂题泛化为 `assessment_item` 条目表（题型+引用+顺序+分值）；答题卡（`assessment_answer_sheet`）草稿暂存、交卷时经纯函数 `QuizGrader` 判分落明细；程序题判题/复查链路不动，仅最终分换算为分值口径。

**Tech Stack:** Java 21 + Spring Boot（JdbcTemplate、Map DTO、tools.jackson）、Flyway + MySQL 8、Testcontainers 集成测试；前端 Vue 3 script setup + Element Plus + vitest，`xlsx` 库解析导入。

**Spec:** `docs/superpowers/specs/2026-10-24-mixed-exam-quiz-design.md`（本计划的一切口径以 spec 为准，含 §5 数据模型、§6 API、§7 导入、§8 页面）

## Global Constraints

- 后端不引入新 DTO 框架：沿用 `Map<String,Object>` 请求/响应 + `ApiResponse` 包装；JSON 用 `tools.jackson.databind.json.JsonMapper`。
- 所有写操作走 `IdempotencyService`（写接口）+ `assessment_audit` 审计；事务用 `TransactionTemplate`。
- 学生不可见的资源统一抛 `ApiException(404, ErrorCode.NOT_FOUND)`，不抛 403（沿用 `AssessmentServiceImpl.requireVisible` 口径）。
- 数据库迁移：`backend/src/main/resources/db/migration/V12__*.sql`，编号顺延，禁止改历史迁移。
- API 契约：新端点同步写入 `docs/api/openapi.yaml`，`ImplementedApiContractIT` 必须保持通过。
- 后端测试命令：`cd backend && ./mvnw test -Dtest=<ClassName>`；前端：`cd frontend && npm run test -- <path>`。
- 前端沿用现有约定：视图文件头中文块注释说明用途；API 模块放 `src/api/modules/`，类型加在 `src/api/types.ts`；路由角色用 `routes.ts` 里的 `MEMBER_ROLES`（STUDENT+DIRECTION_LEADER）/`STAFF_ROLES`（COLLEGE_ADMIN+DIRECTION_LEADER）常量。
- 提交信息沿用 `feat(oj): …` / `test(oj): …` 中文主题，每任务一提交。

## Review Focus

1. **试卷接口泄漏答案**：`GET /assessments/{id}/paper` 响应不得含 `answer`/`analysis` 字段 → Task 5 `paperHidesAnswersAndAnalysis`（序列化全响应做字符串断言）。
2. **交卷后改答案/重复交卷**：SUBMITTED 后 `PUT answer-sheet` 与再次 submit 必须拒绝 → Task 5 `saveDraftRejectsAfterSubmit` + `resubmitReturnsStoredResponse`。
3. **窗口外操作**：开始前/结束后保存草稿与交卷必须拒绝（自动补交路径除外）→ Task 5 `draftOutsideWindowRejected`、`submitOutsideWindowRejected`。
4. **学生端客观题保密性**：学生访问 `/quiz-questions` 或他人答题卡一律 404/仅限本人 → Task 2 `studentGets404OnQuizBank`、Task 5 `studentSeesOnlyOwnSheet`。
5. **旧考核总分漂移**：迁移回填 score=100 后，纯程序考核的 member-scores 总分与迁移前完全一致 → Task 7 `itemScore100KeepsLegacyValue`、Task 8 `legacyProgramAssessmentTotalsUnchanged`。

---

### Task 1: V12 迁移——客观题/条目/答题卡四张表

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__create_quiz_questions_and_assessment_items.sql`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/QuizSchemaIT.java`（照 `AssessmentSchemaIT` 的 Testcontainers 模式）

**Interfaces:**
- Produces: 表 `quiz_question`、`assessment_item`、`assessment_answer_sheet`、`assessment_answer_sheet_item`；`assessment_problem_link` 被删除（后续任务全部改用 `assessment_item`）。

- [ ] **Step 1: 写失败的模式测试**

`QuizSchemaIT` 断言：迁移后四张新表存在、`assessment_problem_link` 不存在；预置 `assessment`+`assessment_problem`+`assessment_problem_link` 行后迁移，断言其被搬入 `assessment_item` 且 `score=100`、`position` 保持；插入同考核重复 `problem_id` 的条目抛约束异常；`problem_id`/`question_id` 同非空抛 CHECK 异常。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw test -Dtest=QuizSchemaIT`
Expected: FAIL（表不存在）

- [ ] **Step 3: 写 V12 迁移 SQL**

关键列（全部口径来自 spec §5）：`quiz_question(type CHECK IN ('SINGLE_CHOICE','MULTI_CHOICE','TRUE_FALSE','FILL_BLANK'), stem TEXT, options JSON NULL, answer JSON NOT NULL, analysis VARCHAR(1000) NULL, tags JSON NOT NULL, status CHECK IN ('DRAFT','PUBLISHED','RETIRED') DEFAULT 'DRAFT', created_by FK app_user, created_at/updated_at DATETIME(6), KEY idx_quiz_question_status_type(status,type))`；`assessment_item(assessment_id FK, position INT, item_type CHECK IN ('PROGRAM','QUIZ'), problem_id BIGINT NULL FK assessment_problem, question_id BIGINT NULL FK quiz_question, score INT NOT NULL CHECK BETWEEN 1 AND 100, PRIMARY KEY(assessment_id,position), UNIQUE uk_item_problem(assessment_id,problem_id), UNIQUE uk_item_question(assessment_id,question_id), CHECK 恰好一引用非空)`；`assessment_answer_sheet(id PK, assessment_id FK, member_id FK, answers JSON NULL, status CHECK IN ('DRAFT','SUBMITTED') DEFAULT 'DRAFT', submitted_at NULL, total_score INT NULL, graded_at NULL, created_at/updated_at, UNIQUE uk_answer_sheet(assessment_id,member_id))`；`assessment_answer_sheet_item(sheet_id FK … ON DELETE CASCADE, position INT, question_id FK quiz_question, answer JSON NULL, correct TINYINT NOT NULL, awarded INT NOT NULL, PRIMARY KEY(sheet_id,position))`。先建新表，再 `INSERT INTO assessment_item(assessment_id,position,item_type,problem_id,score) SELECT assessment_id,position,'PROGRAM',problem_id,100 FROM assessment_problem_link ORDER BY assessment_id,position`，最后 `DROP TABLE assessment_problem_link`。

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && ./mvnw test -Dtest=QuizSchemaIT,FlywayMigrationIT,AssessmentSchemaIT`
Expected: PASS（新迁移不破坏历史模式测试）

- [ ] **Step 5: 提交**

`git add backend/src/main/resources/db/migration/V12__*.sql backend/src/test/java/.../QuizSchemaIT.java && git commit -m "feat(oj): V12 迁移新增客观题/考核条目/答题卡表"`

---

### Task 2: 客观题库服务与控制器（仅负责人/管理员）

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/service/QuizQuestionService.java`、`service/impl/QuizQuestionServiceImpl.java`、`controller/QuizQuestionController.java`
- Modify: `docs/api/openapi.yaml`（新增 `/api/v1/quiz-questions` 路径与 Schema）
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/QuizQuestionHttpIT.java`（HTTP 模式照 `AssessmentManagementHttpIT`）

**Interfaces:**
- Produces:

```java
public interface QuizQuestionService {
  IdempotencyService.StoredResponse create(Map<String, Object> request, SessionPrincipal actor, String key);
  Map<String, Object> get(long id, SessionPrincipal actor);
  Map<String, Object> list(SessionPrincipal actor, int page, int size, String type, String status, String keyword);
  Map<String, Object> update(long id, Map<String, Object> request, SessionPrincipal actor);
  IdempotencyService.StoredResponse retire(long id, Map<String, Object> request, SessionPrincipal actor, String key);
}
```

控制器路由：`GET/POST /api/v1/quiz-questions`、`GET/PATCH /{id}`、`POST /{id}/retire`。响应键：`{id,type,stem,options,answer,analysis,tags,status,createdAt,updatedAt}`（题库管理端可见 answer）。

- [ ] **Step 1: 写失败的 HTTP 集成测试**

用例与断言（spec §6.1）：`studentGets404OnQuizBank`（STUDENT 请求 list/create/get/retire 全部 404）；`createValidatesEachType`（单选 answer="B" 合法、越界字母 400；多选 ["A","C"] 合法、含重复字母 400；判断 answer=true 且带 options 时 400；填空 answer=["x","y"] 合法、空串备选 400；options 少于 2 或多于 6 个 400；stem 空白 400）；`retiredQuestionRejectedByAssessmentImport`（RETIRED 的题出现在 Task 3 场景，此处只测 `retireHidesFromList`：retire 后 list 默认不含）；`publishedQuestionRejectsEdit`（PATCH 非法 409 INVALID_STATE——本任务先把状态置 PUBLISHED 再 PATCH 验证，发布联动在 Task 3 打通）；`leaderSeesAllQuizQuestions`（DIRECTION_LEADER 不限方向）。合法创建用 Idempotency-Key 回放断言 201 复用。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw test -Dtest=QuizQuestionHttpIT`
Expected: FAIL（404，路由不存在）

- [ ] **Step 3: 实现服务与控制器**

权限检查仿 `requireManageDirection`：STUDENT 抛 `ApiException(404, NOT_FOUND)`；校验矩阵按 Step 1 逐条实现（单选 answer 为一个字母、多选为 1–6 个去重字母、判断为 Boolean 且 options 必须 null、填空为 1–10 个非空备选串）；update 仅允许 DRAFT（409）且禁止改 `type`（400）；audit `resource_type='QUIZ_QUESTION'`，动作 `QUIZ_QUESTION_CREATED/UPDATED/RETIRED`；`list` 支持 type/status 过滤 + stem 关键字 LIKE 分页，默认排除 RETIRED。

- [ ] **Step 4: 运行确认通过（含契约）**

Run: `cd backend && ./mvnw test -Dtest=QuizQuestionHttpIT,ImplementedApiContractIT`
Expected: PASS（openapi.yaml 更新后契约测试通过）

- [ ] **Step 5: 提交**

`git commit -m "feat(oj): 新增客观题库服务与端点，仅负责人和管理员可见"`

---

### Task 3: 考核挂题条目化与发布联动

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentServiceImpl.java`（`saveProblems`→`saveItems`、载荷解析、publish 联动、`response`）、`docs/api/openapi.yaml`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/AssessmentManagementHttpIT.java`（扩展）

**Interfaces:**
- Consumes: Task 1 的 `assessment_item`、Task 2 的 `quiz_question`。
- Produces: create/update 载荷 `items: [{type:'PROGRAM'|'QUIZ', refId:'123', score:5}]`（必填非空，替代 `problemIds`）；`GET /assessments/{id}` 响应新增 `items: [{position,type,refId,score}]`（按 position 排序），保留派生的 `problemIds`（PROGRAM 条目 refId 序列）供现有学生视图兼容。

- [ ] **Step 1: 写失败的集成测试**

用例：`createWithMixedItemsSucceeds`（程序题+客观题混排，响应 items 顺序与分值正确，problemIds 仅含程序题）；`duplicateItemRejected`（同 type+refId 两次出现 → 400）；`invalidScoreRejected`（0/101/小数 → 400）；`retiredOrMissingRefRejected`（RETIRED 客观题或不存在 id → 400）；`publishFlipsQuizQuestionsToPublished`（发布后所挂客观题 status=PUBLISHED）；`publishedAssessmentRejectsItemChange`（PUBLISHED 后 PATCH items → 409，沿用现有状态策略）。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw test -Dtest=AssessmentManagementHttpIT`
Expected: FAIL（items 载荷不被接受）

- [ ] **Step 3: 改造 AssessmentServiceImpl**

`items(Object)` 解析器逐条校验（type 枚举、refId 数字串、score 整数 1–100、引用存在且 status!=RETIRED、(type,refId) 去重），替换 `problemIds` 解析与 `saveProblems`（DELETE 全量+INSERT `assessment_item`，沿用现有事务模板）；publish 分支现有的"联动程序题 DRAFT→PUBLISHED"UPDATE 改为 join `assessment_item`，并新增同构语句 `UPDATE quiz_question q JOIN assessment_item i ON i.question_id=q.id AND i.assessment_id=? SET q.status='PUBLISHED',updated_at=? WHERE q.status='DRAFT'`；`response()` 输出 items 与派生 problemIds；旧 `problemIds` 载荷不再接受（400）。

- [ ] **Step 4: 运行确认通过（含受影响的既有套件）**

Run: `cd backend && ./mvnw test -Dtest=AssessmentManagementHttpIT,AssessmentSubmissionHttpIT,AssessmentResultHttpIT,ImplementedApiContractIT`
Expected: PASS

- [ ] **Step 5: 提交**

`git commit -m "feat(oj): 考核挂题泛化为条目，支持客观题混排与发布联动"`

---

### Task 4: 纯函数判分引擎 QuizGrader

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/QuizGrader.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/assessment/QuizGraderTest.java`（纯单元测试，无 Spring）

**Interfaces:**
- Produces:

```java
public final class QuizGrader {
  public record ItemSpec(int position, long questionId, int score) {}
  public record QuestionSpec(String type, List<String> options, Object answer) {}
  public record Graded(int position, long questionId, boolean correct, int awarded) {}
  public static List<Graded> grade(List<ItemSpec> items, Map<Long, QuestionSpec> questions, Map<Integer, Object> answers)
}
```

- [ ] **Step 1: 写失败的单元测试**

规则矩阵（spec §2 判分结论，测试值直接钉死）：单选 `"A"` 对/错；多选 `["A","B","D"]` 全对得满分、漏选 B → 0、多选 C → 0、乱序 `["D","A","B"]` 算全对；判断 `true==true` 对；填空学生答案 `"  answer "` 命中备选 `"answer"`（trim 后比对）但 `"Answer"` 不命中（区分大小写）、命中任一备选即对；未作答（answers 缺 position 或值为 null）→ correct=false、awarded=0；awarded=score 或 0。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw test -Dtest=QuizGraderTest`
Expected: FAIL（类不存在）

- [ ] **Step 3: 实现 grade()**

选项字母 = 索引转 `'A'+i`；单选/判断做等值比对；多选两边转字母 Set 比相等；填空 String.trim() 与备选逐个 trim() equals；未命中按 0 分返回。纯静态、无 IO。

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && ./mvnw test -Dtest=QuizGraderTest`
Expected: PASS

- [ ] **Step 5: 提交**

`git commit -m "feat(oj): 新增客观题纯函数判分引擎"`

---

### Task 5: 答题卡服务、试卷接口与控制器

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/service/AnswerSheetService.java`、`service/impl/AnswerSheetServiceImpl.java`、`controller/AnswerSheetController.java`
- Modify: `docs/api/openapi.yaml`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/QuizAnswerSheetHttpIT.java`

**Interfaces:**
- Consumes: Task 3 的 items、Task 4 的 `QuizGrader.grade`。
- Produces:

```java
public interface AnswerSheetService {
  Map<String, Object> paper(long assessmentId, SessionPrincipal actor);
  Map<String, Object> saveDraft(long assessmentId, Map<String, Object> request, SessionPrincipal actor);
  IdempotencyService.StoredResponse submit(long assessmentId, SessionPrincipal actor, String key);
  Map<String, Object> mySheet(long assessmentId, SessionPrincipal actor);
  Map<String, Object> sheets(long assessmentId, SessionPrincipal actor, int page, int size, Long memberId);
}
```

路由：`GET /api/v1/assessments/{id}/paper`、`PUT /api/v1/assessments/{id}/answer-sheet`（body `{answers}`）、`POST .../answer-sheet/submit`、`GET /api/v1/assessments/{id}/my-answer-sheet`（本人）、`GET /api/v1/assessments/{id}/answer-sheets?memberId=`（staff）。answers JSON 形如 `{"3":"A","5":["A","B"],"7":true,"9":"text"}`（键=position）。

- [ ] **Step 1: 写失败的集成测试**

用例（含 Review Focus 1–4）：`paperHidesAnswersAndAnalysis`（响应序列化字符串断言不含 `"answer"`/`"analysis"` 键值内容，且客观题含 stem/options、程序题含 id/title/difficulty/score）；`draftOutsideWindowRejected` 与 `submitOutsideWindowRejected`（开始前 409 INVALID_STATE）；`saveDraftRejectsAfterSubmit`（SUBMITTED 后 PUT → 409）；`resubmitReturnsStoredResponse`（同 key 重放返回首次结果）；`submitGradesMixedPaper`（种 1 单选对+1 多选漏选+1 填空 trim 命中+1 未答，断言各 `awarded` 与 `total_score` 恰为 spec 值，明细行落 `assessment_answer_sheet_item`）；`studentSeesNoCorrectnessBeforeClosed`（SUBMITTED 后 my-answer-sheet 只有 status/answeredCount，无 correct/awarded/analysis；考核 close 后出现全量明细+解析）；`staffSeesCorrectnessImmediately`（answer-sheets 立即含对错/得分/正确答案）；`studentSeesOnlyOwnSheet`（memberId 过滤对 STUDENT 强制为本人，他人 404）；`studentPaperRequiresPublishedAndOwnDirection`（DRAFT 考核 404）。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw test -Dtest=QuizAnswerSheetHttpIT`
Expected: FAIL（路由不存在）

- [ ] **Step 3: 实现服务与控制器**

`paper`：复用 `requireVisible` 口径（STUDENT 限 PUBLISHED+本方向）取 items，客观题 join `quiz_question` 读 stem/options/tags（**不 select answer/analysis 列**），程序题读 problem 元信息，附本人草稿 answers/status 与窗口剩余；`saveDraft`：窗口内且 DRAFT 才可整体覆盖 upsert，逐 position 校验值形态与题型匹配（单选字母在界内、多选字母数组、判断布尔、填空字符串，未知 position 400）；`submit`：窗口开放 + DRAFT→SUBMITTED（CAS 更新防并发）→ 组装 `ItemSpec/QuestionSpec` 调 `QuizGrader.grade` → 写明细与 total_score/graded_at/submitted_at → audit `QUIZ_SHEET_SUBMITTED`；`mySheet`：CLOSED/ARCHIVED 前剥离判分字段；`sheets`：仅 staff，分页列成员+得分，`memberId` 时附逐题明细（含正确答案与解析）。

- [ ] **Step 4: 运行确认通过（含契约）**

Run: `cd backend && ./mvnw test -Dtest=QuizAnswerSheetHttpIT,ImplementedApiContractIT`
Expected: PASS

- [ ] **Step 5: 提交**

`git commit -m "feat(oj): 新增试卷/答题卡端点，交卷统一自动判分"`

---

### Task 6: 截止自动交卷调度与提前关闭联动

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/service/QuizAnswerSheetScheduler.java`
- Modify: `service/AnswerSheetService.java`（+2 方法）、`service/impl/AnswerSheetServiceImpl.java`、`service/impl/AssessmentServiceImpl.java`（close 命令）
- Test: 扩展 `QuizAnswerSheetHttpIT.java`（+2 用例）与 `backend/src/test/java/io/github/dream07code/innovationspace/assessment/QuizAnswerSheetSchedulerTest.java`（调度委托单测）

**Interfaces:**
- Consumes: Task 5 的判分内部路径。
- Produces: `AnswerSheetService` 新增 `int submitExpiredDrafts()`（扫描 `end_at<now` 考核的 DRAFT 答题卡补交判分，返回处理数）与 `int submitAllDrafts(long assessmentId)`（不限窗口，供 close 调用）；`QuizAnswerSheetScheduler` 为 `@Component`，`@Scheduled(fixedDelayString="${app.quiz.sheet-sweep-interval-ms:30000}")` 的 `sweep()` 委托 `submitExpiredDrafts()`（调度模式照 `ScoringWorkerScheduler`）。

- [ ] **Step 1: 写失败的测试**

`closeForceSubmitsDraftSheets`（学生留 DRAFT，staff 调 `/close` 后该生成 SUBMITTED 且 total_score 已判出——spec §6.3"提前关闭=提前截止"）；`sweepSubmitsExpiredDrafts`（种 end_at 已过的考核+DRAFT 答题卡，直接调 `submitExpiredDrafts()` 断言补交）；调度器单测：mock service 断言 `sweep()` 调用一次 `submitExpiredDrafts()`。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw test -Dtest=QuizAnswerSheetHttpIT,QuizAnswerSheetSchedulerTest`
Expected: FAIL

- [ ] **Step 3: 实现**

两个新方法复用 submit 的内部判分落库路径（抽私有 `gradeAndStore(sheetId)`）；`AssessmentServiceImpl` 的 close 分支在状态翻转后、同一事务内调 `submitAllDrafts(id)`；`QuizAnswerSheetScheduler` 加 `@Scheduled` 组件（无需新配置类，`@EnableScheduling` 已由现有调度器证明在位）。

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && ./mvnw test -Dtest=QuizAnswerSheetHttpIT,QuizAnswerSheetSchedulerTest,ScoringWorkerSchedulerTest`
Expected: PASS

- [ ] **Step 5: 提交**

`git commit -m "feat(oj): 截止自动交卷调度与提前关闭强制判分"`

---

### Task 7: 程序题最终分换算分值口径

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentEvaluationServiceImpl.java`
- Test: 扩展 `backend/src/test/java/io/github/dream07code/innovationspace/assessment/AssessmentScoringTest.java` + `AssessmentScoringHttpIT.java` 各补用例

**Interfaces:**
- Consumes: Task 1 的 `assessment_item.score`。
- Produces: `refreshResult` 写入的 `final_score` 为分值口径；`auto_score` 列保持 0–100 百分比不变（`autoScore()` 原样保留）。

- [ ] **Step 1: 写失败的测试**

`scaledFinalScoreUsesItemScore`（itemScore=20、通过 2/3 → final=13，即 `Math.round(20*2/3.0)`）；`humanReviewScalesByItemScore`（itemScore=7、人工 85 → final=`Math.round(7*85/100.0)`=6）；`itemScore100KeepsLegacyValue`（score=100 → final 与旧百分数逐例相等，含 INTERNAL_ERROR/无测试点仍为 null 待复核）；`needsReviewStillNull`（无自动分且无人工分 → final_score=null、NEEDS_REVIEW）。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw test -Dtest=AssessmentScoringTest`
Expected: FAIL（仍是 0–100 口径）

- [ ] **Step 3: 实现换算**

`refreshResult` 内查 `SELECT score FROM assessment_item WHERE assessment_id=(SELECT assessment_id FROM assessment_submission WHERE id=?) AND problem_id=(SELECT problem_id FROM assessment_submission WHERE id=?) AND item_type='PROGRAM'`（缺行按 100 兜底，防御旧数据）；final = human!=null ? `Math.round(itemScore*humanScore/100.0)` : auto!=null ? `Math.round(itemScore*auto/100.0)` : null；其余列与状态机不动。

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && ./mvnw test -Dtest=AssessmentScoringTest,AssessmentScoringHttpIT,AssessmentResultHttpIT`
Expected: PASS

- [ ] **Step 5: 提交**

`git commit -m "feat(oj): 程序题最终分按考核条目分值换算"`

---

### Task 8: member-scores 汇总改分值口径

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentResultServiceImpl.java`（`memberScores`）
- Test: 扩展 `AssessmentResultHttpIT.java` 或新增 `QuizResultAggregationHttpIT.java`

**Interfaces:**
- Consumes: Task 5 的答题卡得分、Task 7 的分值口径 final_score。
- Produces: `memberScores` 响应字段变为 `{memberId,memberName,memberStudentNo,itemCount,answeredCount,completedCount,pendingCount,totalScore,maxScore,scoreRate,lastSubmittedAt}`；删除 `problemCount`、`averageScore`（spec §5.5 精确定义）。

- [ ] **Step 1: 写失败的集成测试**

`mixedAggregationTotals`：种 2 程序题(20+30 分)+2 客观题(5+5 分)的考核，学生 A 交答题卡(得 8)+程序题各提交一次(COMPLETE final 13 与 27)——断言 itemCount=4、answeredCount=4、completedCount=4、totalScore=48、maxScore=60、scoreRate=80；未作答学生 totalScore=0、answeredCount=0。`legacyProgramAssessmentTotalsUnchanged`：纯程序考核（迁移路径 score=100）下总分与旧口径期望逐分相等（Review Focus 5）。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw test -Dtest=AssessmentResultHttpIT`
Expected: FAIL（字段仍是旧口径）

- [ ] **Step 3: 改写聚合 SQL 与装配**

在现有 latest-per-problem CTE 之外 LEFT JOIN `assessment_answer_sheet`（含 total_score、answers、status、graded_at）；`itemCount`/`maxScore` 由 `assessment_item` 聚合（`COUNT/SUM(score)`）；程序题 final 求和 + sheet.total_score 得 totalScore；answeredCount = 有提交程序题数 + 答题卡 answers 中非空值个数（answers JSON 拉到 Java 侧计数，避免在 SQL 里解 JSON）；completedCount = 最后一次提交 COMPLETE 数 + 已判分明细条数（SUBMITTED 且 graded_at 非空时取明细行数）；scoreRate = maxScore>0 ? `Math.round(totalScore*100.0/maxScore)` : 0。

- [ ] **Step 4: 运行确认通过**

Run: `cd backend && ./mvnw test -Dtest=AssessmentResultHttpIT,AssessmentReviewHttpIT`
Expected: PASS

- [ ] **Step 5: 提交**

`git commit -m "feat(oj): 按学生汇总改分值口径并新增得分率"`

---

### Task 9: 前端 API 模块与类型

**Files:**
- Create: `frontend/src/api/modules/quiz.ts`
- Modify: `frontend/src/api/types.ts`（新增类型）、`frontend/src/api/modules/oj.ts`（`Assessment` 类型侧的 items 字段若放 types.ts 则只改 types）
- Test: `frontend/src/api/modules/__tests__/quiz.spec.ts`（照 `oj.spec.ts` 模式）

**Interfaces:**
- Produces（函数签名，全部薄封装 `get/post/patch`）：`listQuizQuestions(params:{page?,size?,type?,status?,keyword?})`、`createQuizQuestion(payload,key)`、`getQuizQuestion(id)`、`updateQuizQuestion(id,payload)`、`retireQuizQuestion(id,key)`、`getAssessmentPaper(assessmentId)`、`saveAnswerDraft(assessmentId,answers)`（PUT）、`submitAnswerSheet(assessmentId,key)`、`getMyAnswerSheet(assessmentId)`、`listAnswerSheets(assessmentId,params)`。类型：`QuizQuestionType='SINGLE_CHOICE'|'MULTI_CHOICE'|'TRUE_FALSE'|'FILL_BLANK'`、`QuizQuestion`、`CreateQuizQuestionRequest`、`AssessmentItem{position,type:'PROGRAM'|'QUIZ',refId,score}`、`UpdateAssessmentRequest` 的 items 字段、`AssessmentPaper`、`PaperItem`、`AnswerDraft`（Record<string,unknown>）、`AnswerSheetSummary`、`AnswerSheetDetail`。

- [ ] **Step 1: 写失败的模块测试**（断言各函数命中正确方法与路径，写操作带头部 Idempotency-Key）
- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && npm run test -- src/api/modules/__tests__/quiz.spec.ts`
Expected: FAIL

- [ ] **Step 3: 实现 quiz.ts 与 types.ts 类型**
- [ ] **Step 4: 运行确认通过**（同 Step 2 命令，Expected: PASS）
- [ ] **Step 5: 提交** `git commit -m "feat(oj): 前端客观题与答题卡 API 模块"`

---

### Task 10: 客观题库管理页（列表 + 四题型编辑）

**Files:**
- Create: `frontend/src/views/oj/QuizQuestionListView.vue`、`frontend/src/views/oj/QuizQuestionEditView.vue`
- Modify: `frontend/src/router/routes.ts`（`/oj` 子路由：`quiz-bank`、`quiz-bank/new`、`quiz-bank/:id/edit`，均 `meta:{roles:STAFF_ROLES,…}`）
- Test: `frontend/src/views/oj/__tests__/QuizQuestionListView.spec.ts`、`QuizQuestionEditView.spec.ts`

**Interfaces:**
- Consumes: Task 9 的 `listQuizQuestions/createQuizQuestion/getQuizQuestion/updateQuizQuestion/retireQuizQuestion`。

- [ ] **Step 1: 写失败的组件测试**

列表 spec：渲染分页行（type 中文标签/题干截断/status）、type 过滤与关键字触发请求、retire 确认后刷新；编辑 spec：按题型切换表单（单选=选项数组+单选答案；多选=选项数组+多选框答案；判断=对/错单选且无选项区；填空=备选答案多行），提交组装 payload（多选答案字母升序数组、填空备选数组），编辑态回填，DRAFT 之外禁用保存。

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && npm run test -- src/views/oj/__tests__/QuizQuestion*.spec.ts`
Expected: FAIL

- [ ] **Step 3: 实现两视图与路由**（表单校验沿 Element Plus rules 风格；题型中文映射 单选/多选/判断/填空）
- [ ] **Step 4: 运行确认通过**（同 Step 2，Expected: PASS）
- [ ] **Step 5: 提交** `git commit -m "feat(oj): 客观题库管理页（负责人/管理员）"`

---

### Task 11: Excel 批量导入客观题

**Files:**
- Create: `frontend/src/components/quiz/QuizQuestionImportDialog.vue`
- Modify: `frontend/src/views/oj/QuizQuestionListView.vue`（"批量导入"按钮开对话框）
- Test: `frontend/src/views/oj/__tests__/QuizQuestionImportDialog.spec.ts`

**Interfaces:**
- Consumes: Task 9 的 `createQuizQuestion`（逐行、每行独立 Idempotency-Key）；模板下载与解析照 `MemberBatchImportView.vue` 的 XLSX 用法。

- [ ] **Step 1: 写失败的组件测试**

模板列头恰为 `题型|题干|选项A|选项B|选项C|选项D|选项E|选项F|答案|解析|标签`；行映射：题型 单选/多选/判断/填空，判断答案 对/错→boolean，多选答案 "ABD"→["A","B","D"]，填空答案 "a|b"→["a","b"]，标签 "、"/","分隔；非法行（未知题型/答案越界/选项不足）标错并继续处理其余行；结果表展示每行 成功/失败原因；模板下载函数被调用时生成含表头 sheet。

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && npm run test -- src/views/oj/__tests__/QuizQuestionImportDialog.spec.ts`
Expected: FAIL

- [ ] **Step 3: 实现对话框**（导入中禁用关闭；全部行处理完显示汇总 N 成功 / M 失败）
- [ ] **Step 4: 运行确认通过**（同 Step 2，Expected: PASS）
- [ ] **Step 5: 提交** `git commit -m "feat(oj): 客观题 Excel 批量导入"`

---

### Task 12: 学生试卷页 ExamPaperView

**Files:**
- Create: `frontend/src/views/oj/ExamPaperView.vue`
- Modify: `frontend/src/router/routes.ts`（`/oj/assessments/:id/paper`，`meta:{roles:MEMBER_ROLES,…}`）、`frontend/src/views/oj/AssessmentDetailView.vue`（PUBLISHED 且有条目时显示"进入考试/查看试卷"入口）
- Test: `frontend/src/views/oj/__tests__/ExamPaperView.spec.ts`

**Interfaces:**
- Consumes: Task 9 的 `getAssessmentPaper/saveAnswerDraft/submitAnswerSheet/getMyAnswerSheet`，Task 3 的 items 顺序。

- [ ] **Step 1: 写失败的组件测试**

按 position 渲染条目：客观题出对应作答控件（单选 radio / 多选 checkbox / 判断 对错 / 填空 input），程序题卡片显示题号分值并给"去作答"跳转（router push 既有提交路由）；答案变化 2s 防抖调用 saveAnswerDraft；已 SUBMITTED 只读且显示"已交卷"；点交卷弹确认框，确认后调 submitAnswerSheet 并锁定；试卷外（非 PUBLISHED）显示不可用提示。

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && npm run test -- src/views/oj/__tests__/ExamPaperView.spec.ts`
Expected: FAIL

- [ ] **Step 3: 实现试卷页**（窄屏单列、桌面单页滚动，文件头块注释说明用途；草稿状态经 my-answer-sheet 回填）
- [ ] **Step 4: 运行确认通过**（同 Step 2，Expected: PASS）
- [ ] **Step 5: 提交** `git commit -m "feat(oj): 学生混合试卷作答页"`

---

### Task 13: 考核编辑页条目化

**Files:**
- Modify: `frontend/src/views/oj/AssessmentEditView.vue`（problemIds 多选 → 条目编辑器）、`frontend/src/api/modules/oj.ts`（创建/更新调用改传 items，函数签名不变）
- Test: Create `frontend/src/views/oj/__tests__/AssessmentEditView.spec.ts`（现无该 spec，新建）

**Interfaces:**
- Consumes: Task 9 类型 `AssessmentItem`；`listAssessmentProblems` 与 `listQuizQuestions` 两个题库源。

- [ ] **Step 1: 写失败的组件测试**

两个题库选择器（程序题/客观题，过滤 RETIRED）添加条目进列表；条目表支持 上移/下移/删除 与分值 `el-input-number`(1–100)；底部实时显示合计分值；重复添加同题被拒绝并提示；提交 payload 为 `items:[{type,refId,score}]` 依列表顺序；编辑态从 `assessment.items` 回填。

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && npm run test -- src/views/oj/__tests__/AssessmentEditView.spec.ts`
Expected: FAIL

- [ ] **Step 3: 改造编辑页**（保存前校验至少 1 条目且每条分值有效，沿用现有 ElMessage 错误提示风格）
- [ ] **Step 4: 运行确认通过**（同 Step 2，Expected: PASS）
- [ ] **Step 5: 提交** `git commit -m "feat(oj): 考核编辑支持混合条目与逐题分值"`

---

### Task 14: 结果/详情页分值口径与答题卡下钻、全量回归

**Files:**
- Modify: `frontend/src/views/oj/AssessmentResultsView.vue`、`frontend/src/views/oj/AssessmentDetailView.vue`
- Test: 扩展 `frontend/src/views/oj/__tests__/AssessmentResultsView.spec.ts`

**Interfaces:**
- Consumes: Task 8 的 `scoreRate/maxScore/itemCount` 字段、Task 9 的 `listAnswerSheets`。

- [ ] **Step 1: 写失败的组件测试**

结果页列改 `完成 X / itemCount`、`总分 totalScore / maxScore`、`得分率 scoreRate%`（原 averageScore 列删除）；staff 行点击"答题卡"打开下钻对话框，渲染逐题 题干/学生答案/正确答案/得分/解析；学生本人无下钻入口；明细数据来自 `listAnswerSheets(assessmentId,{memberId})`。

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && npm run test -- src/views/oj/__tests__/AssessmentResultsView.spec.ts`
Expected: FAIL

- [ ] **Step 3: 实现改造**（AssessmentDetailView 试卷结构列表：题号+题型图标+标题/题干截断+分值）
- [ ] **Step 4: 全量回归**

Run: `cd backend && ./mvnw test`（Expected: BUILD SUCCESS）
Run: `cd frontend && npm run test`（Expected: 全部 PASS）

- [ ] **Step 5: 提交** `git commit -m "feat(oj): 结果页分值口径与答题卡明细下钻"`
