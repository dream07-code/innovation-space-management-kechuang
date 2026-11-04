# 考核填空人工阅卷与成绩发布实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use subagent-driven-development (recommended) or executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让考核里的填空题支持多行代码作答、按题横向人工评分，并在阅卷完成后由负责人/管理员手动发布成绩、同时结算考核积分。

**Architecture:** 在现有答题卡链路上加一层“人工覆盖 + 发布”能力：V16 迁移给答题卡明细加自动分/人工分/批阅留痕字段，给考核加发布标记；新增 `QuizReviewService` 负责阅卷列表、批量评分、撤销与完成度统计；`AssessmentService` 新增自动关闭与发布成绩，积分结算从“关闭时”移到“发布时”；学生侧所有分数读取按发布标记过滤。前端新增阅卷页，试卷填空改多行文本域，成绩页加发布入口。

**Tech Stack:** Java 21、Spring Boot 4.1.1、JdbcTemplate、Flyway、MySQL 8.0、Vue 3、Element Plus、Vitest。

**Spec:** `docs/superpowers/specs/2026-11-04-quiz-manual-grading-design.md`

## Global Constraints

- 只有 `FILL_BLANK` 可人工评分；单选/多选/判断仍是自动判分终判，传入其他题型返回 400。
- 人工分范围 `0..assessment_item.score`，是覆盖不是相加；未人工评分时 `awarded = auto_awarded`。
- 填空答案长度上限 20000 字符（前端 `maxlength` 与后端校验一致），空/纯空白仍拒绝。
- 阅卷只在考核 `CLOSED` 或 `ARCHIVED` 且 `results_published_at IS NULL` 时开放；发布后阅卷与发布都返回 409。
- 发布前置：`status=CLOSED`、未发布、无待批填空明细、`version` 匹配。
- 批量评分单次最多 200 条；整体一个事务；任一条版本冲突返回 409 `VERSION_CONFLICT`，不部分提交。
- 权限：`COLLEGE_ADMIN`，或 `managedDirectionIds` 包含考核方向的方向负责人；学生 404。
- 积分在发布成绩时按最终总分结算，事件键 `ASSESSMENT_REWARD:{assessmentId}:{memberId}` 不变。
- 不引入新依赖；不新增常驻进程；JVM/MySQL 内存参数不在本计划内改动。
- 契约两份同步：`docs/api/openapi.yaml` 与 `backend/src/main/resources/api-contract.yaml`。
- 所有时间使用 `Asia/Shanghai` 展示，存储沿用现有 UTC 口径。

## Review Focus

1. **空答案与未作答**：学生没写就交卷时，阅卷列表要出现该行但不可编辑，且不会阻塞“发布成绩”——错误实现会把全场永远卡在“还有 1 条未批”。
2. **超长代码**：粘贴 2 万字符以上代码时，前端应截断提示而不是静默丢内容；后端应返回 400 而不是 500。
3. **两名教师同时批同一题**：后保存的人必须收到 409 并看到刷新提示，不能悄悄覆盖前一个人的分数。
4. **学生绕过页面直接调接口**：未发布时 `my-answer-sheet`、`member-scores`、考核详情都不能出现任何分数或逐题明细。
5. **已归档考核**：历史考核迁移后视为已发布，学生仍能看到旧成绩；对 `ARCHIVED` 再次发布应返回 409 而不是改数据。

---

### Task 1: V16 迁移与模式版本

**Files:**
- Create: `backend/src/main/resources/db/migration/V16__quiz_manual_grading_and_result_publication.sql`
- Modify: `backend/src/main/resources/application.yml`（`app.migration.required-version: "15"` → `"16"`）
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/QuizManualGradingSchemaIT.java`

**Interfaces:**
- Produces: 表结构 `assessment.results_published_at/results_published_by`，`assessment_answer_sheet_item.auto_awarded/manual_score/review_comment/reviewed_by/reviewed_at/version`。

- [ ] **Step 1: 写失败的 schema 测试**

在 `QuizManualGradingSchemaIT` 中断言（沿用 `MysqlTestSupport.migrate` + `JdbcTemplate`）：

```java
assertTrue(columns("assessment").containsAll(List.of("results_published_at", "results_published_by")));
assertTrue(columns("assessment_answer_sheet_item").containsAll(List.of(
    "auto_awarded", "manual_score", "review_comment", "reviewed_by", "reviewed_at", "version")));
// 新插入的明细默认 auto_awarded=0、version=1、manual_score 为 NULL
assertEquals(0, jdbc.queryForObject(
    "SELECT auto_awarded FROM assessment_answer_sheet_item WHERE sheet_id=? AND position=?", Integer.class, sheetId, 1));
// 人工评分三字段必须同时有值（违反约束应抛异常）
assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
    "UPDATE assessment_answer_sheet_item SET manual_score=5 WHERE sheet_id=? AND position=?", sheetId, 1));
```

`ARCHIVED` 回填的口径由 Task 9 的手工冒烟覆盖（在已有历史考核的库上执行迁移后检查 `results_published_at` 非空），不在本测试里造历史数据。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw -Dtest=QuizManualGradingSchemaIT test`
Expected: FAIL，列不存在。

- [ ] **Step 3: 写迁移**

```sql
ALTER TABLE assessment
  ADD COLUMN results_published_at DATETIME(6) NULL,
  ADD COLUMN results_published_by BIGINT NULL,
  ADD CONSTRAINT fk_assessment_results_publisher FOREIGN KEY (results_published_by) REFERENCES app_user(id);

ALTER TABLE assessment_answer_sheet_item
  ADD COLUMN auto_awarded INT NOT NULL DEFAULT 0,
  ADD COLUMN manual_score INT NULL,
  ADD COLUMN review_comment VARCHAR(500) NULL,
  ADD COLUMN reviewed_by BIGINT NULL,
  ADD COLUMN reviewed_at DATETIME(6) NULL,
  ADD COLUMN version BIGINT NOT NULL DEFAULT 1,
  ADD CONSTRAINT fk_sheet_item_reviewer FOREIGN KEY (reviewed_by) REFERENCES app_user(id),
  ADD CONSTRAINT ck_sheet_item_manual_score CHECK (manual_score IS NULL OR manual_score >= 0),
  ADD CONSTRAINT ck_sheet_item_review_state CHECK (
    (manual_score IS NULL AND reviewed_by IS NULL AND reviewed_at IS NULL)
    OR (manual_score IS NOT NULL AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL));

UPDATE assessment_answer_sheet_item SET auto_awarded = awarded;
UPDATE assessment SET results_published_at = updated_at
 WHERE status = 'ARCHIVED' AND results_published_at IS NULL;
```

同时把 `application.yml` 的 `required-version` 改为 `"16"`；若有测试把模式版本固定为 `15`（如 `FlywayMigrationIT`、`ApplicationMetadataTest`），同步改为 `16`。

- [ ] **Step 4: 运行通过**

Run: `cd backend && ./mvnw -Dtest=QuizManualGradingSchemaIT test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/resources/db/migration/V16__quiz_manual_grading_and_result_publication.sql \
        backend/src/main/resources/application.yml \
        backend/src/test/java/io/github/dream07code/innovationspace/integration/QuizManualGradingSchemaIT.java
git commit -m "feat(oj): 增加填空人工阅卷与成绩发布的数据结构"
```

---

### Task 2: 阅卷状态纯函数

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/QuizReviewPolicy.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/assessment/QuizReviewPolicyTest.java`

**Interfaces:**
- Produces:
  - `static boolean canGrade(String status, boolean published)`
  - `static boolean canPublish(String status, boolean published, int pendingItems)`
  - `static boolean visibleToStudent(boolean published)`

- [ ] **Step 1: 写失败测试**

```java
@Test void gradingOpenOnlyAfterCloseAndBeforePublish() {
  assertFalse(QuizReviewPolicy.canGrade("PUBLISHED", false));
  assertTrue(QuizReviewPolicy.canGrade("CLOSED", false));
  assertTrue(QuizReviewPolicy.canGrade("ARCHIVED", false));
  assertFalse(QuizReviewPolicy.canGrade("CLOSED", true));
}
@Test void publishRequiresClosedUnpublishedAndNoPending() {
  assertFalse(QuizReviewPolicy.canPublish("PUBLISHED", false, 0));
  assertFalse(QuizReviewPolicy.canPublish("CLOSED", true, 0));
  assertFalse(QuizReviewPolicy.canPublish("CLOSED", false, 1));
  assertTrue(QuizReviewPolicy.canPublish("CLOSED", false, 0));
  assertFalse(QuizReviewPolicy.canPublish("ARCHIVED", false, 0));
}
@Test void studentsSeeResultsOnlyAfterPublish() {
  assertFalse(QuizReviewPolicy.visibleToStudent(false));
  assertTrue(QuizReviewPolicy.visibleToStudent(true));
}
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw -Dtest=QuizReviewPolicyTest test`
Expected: FAIL，类不存在。

- [ ] **Step 3: 实现**

用 `switch (status)` 判定，`ARCHIVED` 允许阅卷但 `canPublish` 返回 false。

- [ ] **Step 4: 运行通过**

Run: `cd backend && ./mvnw -Dtest=QuizReviewPolicyTest test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/github/dream07code/innovationspace/assessment/QuizReviewPolicy.java \
        backend/src/test/java/io/github/dream07code/innovationspace/assessment/QuizReviewPolicyTest.java
git commit -m "feat(oj): 增加阅卷与成绩发布状态判定"
```

---

### Task 3: 阅卷服务与接口

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/service/QuizReviewService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/QuizReviewServiceImpl.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/controller/QuizReviewController.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/QuizManualGradingHttpIT.java`

**Interfaces:**
- Consumes: Task 1 字段、Task 2 `QuizReviewPolicy`。
- Produces:
  - `Map<String,Object> review(long assessmentId, long questionId, String filter, int page, int size, SessionPrincipal actor)`
  - `Map<String,Object> summary(long assessmentId, SessionPrincipal actor)`
  - `int pendingCount(long assessmentId)`（已交卷答题卡中尚无 `manual_score` 的填空明细数）
  - `IdempotencyService.StoredResponse grade(long assessmentId, Map<String,Object> request, SessionPrincipal actor, String key)`
  - `IdempotencyService.StoredResponse revoke(long assessmentId, long sheetId, int position, Map<String,Object> request, SessionPrincipal actor, String key)`

HTTP：
- `GET /api/v1/assessments/{assessmentId}/quiz-review?questionId=&filter=all|pending|auto_zero|manual&page=1&size=50`
- `GET /api/v1/assessments/{assessmentId}/quiz-review/summary`
- `POST /api/v1/assessments/{assessmentId}/quiz-review/scores`（`Idempotency-Key` 必填）
- `POST /api/v1/assessments/{assessmentId}/quiz-review/scores/{sheetId}/{position}/revoke`（`Idempotency-Key` 必填）

- [ ] **Step 1: 写失败的集成测试**

`QuizManualGradingHttpIT` 沿用 `QuizAnswerSheetHttpIT` 的 fixture（Testcontainers、seed 学生/负责人/管理员、一场 `CLOSED` 考核、一张已交卷答题卡）。至少断言：

```java
// 1) 学生访问阅卷列表 -> 404
// 2) 其他方向负责人 -> 404
// 3) PUBLISHED 状态阅卷 -> 409
// 4) 管理员 GET quiz-review 返回 50 行以内、题目含 referenceAnswers、行含 autoAwarded/awarded/version
// 5) POST scores 一条 score=8 -> 200；再 GET 明细 awarded=8、manualScore=8、reviewed=true；answer_sheet.total_score 同步
// 6) 同一条用旧 version 再 POST -> 409 VERSION_CONFLICT，且数据库值未变
// 7) score > item.score 或 < 0 -> 400
// 8) 未作答（无明细行）的 sheetId/position -> 400
// 9) 带 SAME Idempotency-Key 重放 -> 返回首次结果，不重复写审计
// 10) revoke 带 version -> awarded 回到 auto_awarded；不带 version -> 400
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw -Dtest=QuizManualGradingHttpIT test`
Expected: FAIL（404/路由不存在；需要 Docker，本机无 Docker 时记录为待 CI 验证）。

- [ ] **Step 3: 实现服务与控制器**

`review` 的查询骨架：

```sql
SELECT s.id AS sheet_id, s.member_id, m.name AS member_name, m.student_no,
       d.answer, d.auto_awarded, d.awarded, d.manual_score, d.review_comment, d.version
FROM assessment_answer_sheet s
JOIN member m ON m.id = s.member_id
JOIN assessment_item i ON i.assessment_id = s.assessment_id AND i.position = ?
LEFT JOIN assessment_answer_sheet_item d ON d.sheet_id = s.id AND d.position = i.position
WHERE s.assessment_id = ? AND s.status = 'SUBMITTED'
ORDER BY m.student_no, s.id
```

`filter` 在 Java 里拼 `d.manual_score IS NULL` / `d.auto_awarded = 0 AND d.manual_score IS NULL` / `d.manual_score IS NOT NULL`；`questionId` 必须属于本场且题型 `FILL_BLANK`。

`grade` 逐条：校验 `sheetId` 属于该考核且已交卷、明细行存在、`score` 在 `0..item.score`、`version` 匹配；更新 `manual_score/awarded/reviewed_by/reviewed_at/version+1`；对该 sheet 加行锁后重算：

```sql
UPDATE assessment_answer_sheet s
   SET total_score = (SELECT COALESCE(SUM(awarded),0) FROM assessment_answer_sheet_item WHERE sheet_id = s.id),
       updated_at = ?
 WHERE s.id = ?
```

写 `assessment_audit`（`QUIZ_ITEM_MANUAL_GRADED`，detail 含 `position/autoAwarded/beforeAwarded/manualScore/version`）。

`revoke` 必须带 `version`，`manual_score=NULL`、`awarded=auto_awarded`、`reviewed_by/at=NULL`、`version+1`，重算总分，写 `QUIZ_ITEM_MANUAL_REVOKED`。

`summary` 返回：

```json
{"totalItems":150,"gradedItems":120,"pendingItems":30,"submittedSheets":50,
 "resultsPublished":false,"canPublish":false,"pointsEnabled":true}
```

`canPublish = QuizReviewPolicy.canPublish(status, published, pendingItems)`。

- [ ] **Step 4: 运行通过**

Run: `cd backend && ./mvnw -Dtest=QuizManualGradingHttpIT test`
Expected: PASS（有 Docker 的环境）。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/github/dream07code/innovationspace/service/QuizReviewService.java \
        backend/src/main/java/io/github/dream07code/innovationspace/service/impl/QuizReviewServiceImpl.java \
        backend/src/main/java/io/github/dream07code/innovationspace/controller/QuizReviewController.java \
        backend/src/test/java/io/github/dream07code/innovationspace/integration/QuizManualGradingHttpIT.java
git commit -m "feat(oj): 支持按题横向批阅填空答案"
```

---

### Task 4: 自动关闭、发布成绩与积分时机

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/AssessmentService.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentServiceImpl.java:176-215`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/controller/AssessmentController.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/assessment/service/QuizAnswerSheetScheduler.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/AssessmentPointsRewardIT.java`

**Interfaces:**
- Consumes: Task 3 的阅卷完成度；`AssessmentPointsRewardService#settle(long)`。
- Produces:
  - `int closeExpiredAssessments()`（`AssessmentService`）
  - `IdempotencyService.StoredResponse publishResults(long id, Map<String,Object> request, SessionPrincipal actor, String key)`
  - `POST /api/v1/assessments/{assessmentId}/publish-results`

- [ ] **Step 1: 改测试（先失败）**

`AssessmentPointsRewardIT`：

```java
// 原“close 后积分到账”改为：
close(assessmentId);                       // 关闭
assertEquals(0, pointsOf(student));        // 未发布前不发
publishResults(assessmentId, version);     // 发布
assertEquals(expected, pointsOf(student)); // 发布会话发放
// 重复发布 -> 409，且 point_ledger 数量不变
```

新增断言：`end_at` 过后调用 `closeExpiredAssessments()`，状态变 `CLOSED` 且不写积分。

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw -Dtest=AssessmentPointsRewardIT test`
Expected: FAIL（发布接口不存在；关闭仍结算）。

- [ ] **Step 3: 实现**

- `AssessmentServiceImpl.command` 的 `CLOSED` 分支保留 `answerSheets.submitAllDrafts(id)`，删除 `pointsRewards.settle(id)`。
- 新增 `closeExpiredAssessments()`：

```sql
UPDATE assessment SET status='CLOSED', version=version+1, updated_at=?
 WHERE status='PUBLISHED' AND end_at < ?
```

- `publishResults`：锁行 → `version` 校验 → `QuizReviewPolicy.canPublish` 校验（pending 用 `QuizReviewService#pendingCount`）→ 写 `results_published_at=now, results_published_by=actor.userId(), version+1` → `pointsRewards.settle(id)`（同一事务）→ 审计 `ASSESSMENT_RESULTS_PUBLISHED`。
- 调度器在 `submitExpiredDrafts()` 之后调用 `closeExpiredAssessments()`，异常同样只记日志。
- 控制器加 `POST /{assessmentId}/publish-results`，复用 `stored(...)`。

- [ ] **Step 4: 运行通过**

Run: `cd backend && ./mvnw -Dtest=AssessmentPointsRewardIT test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/github/dream07code/innovationspace/service/AssessmentService.java \
        backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentServiceImpl.java \
        backend/src/main/java/io/github/dream07code/innovationspace/controller/AssessmentController.java \
        backend/src/main/java/io/github/dream07code/innovationspace/assessment/service/QuizAnswerSheetScheduler.java \
        backend/src/test/java/io/github/dream07code/innovationspace/integration/AssessmentPointsRewardIT.java
git commit -m "feat(oj): 发布成绩时结算积分并自动关闭到期考核"
```

---

### Task 5: 学生可见性按发布过滤

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AnswerSheetServiceImpl.java:148-160, 500-533`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentResultServiceImpl.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentServiceImpl.java#response`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/QuizAnswerSheetHttpIT.java`

**Interfaces:**
- Consumes: `assessment.results_published_at`。
- Produces: 学生侧 `mySheet` 返回 `resultsPublished`；`detailItems` 拆教师/学生两套字段；`Assessment` 响应增加 `resultsPublished/resultsPublishedAt`。

- [ ] **Step 1: 改测试（先失败）**

```java
// 关闭但未发布：
// - GET my-answer-sheet -> totalScore == null，items == null，resultsPublished == false
// - GET member-scores -> sheetScore == null
// 发布后：
// - totalScore 非空，items 中有 awarded 与 reviewComment，但没有 autoAwarded/manualScore
```

- [ ] **Step 2: 运行确认失败**

Run: `cd backend && ./mvnw -Dtest=QuizAnswerSheetHttpIT test`
Expected: FAIL（关闭即返回明细）。

- [ ] **Step 3: 实现**

- `AnswerSheetServiceImpl`：`revealed` 改为 `results_published_at != null`；`detailItems(sheetId, boolean staff)`，学生视图去掉 `autoAwarded/manualScore/reviewed`，保留 `awarded/reviewComment/correctAnswer/analysis`。
- `AssessmentResultServiceImpl#memberScores`：`actor.role=STUDENT` 且未发布时，把 `sheet_score`、`program_score` 置 null，并在返回体加 `resultsPublished=false`。
- `AssessmentServiceImpl#response`：补 `resultsPublished`、`resultsPublishedAt`。

- [ ] **Step 4: 运行通过**

Run: `cd backend && ./mvnw -Dtest=QuizAnswerSheetHttpIT test`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AnswerSheetServiceImpl.java \
        backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentResultServiceImpl.java \
        backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AssessmentServiceImpl.java \
        backend/src/test/java/io/github/dream07code/innovationspace/integration/QuizAnswerSheetHttpIT.java
git commit -m "feat(oj): 学生成绩按发布状态过滤"
```

---

### Task 6: 填空题支持多行代码

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AnswerSheetServiceImpl.java:355-358`（长度 500 → 20000）
- Modify: `frontend/src/views/oj/ExamPaperView.vue:200-207`
- Test: `frontend/src/views/oj/__tests__/ExamPaperView.spec.ts`

**Interfaces:**
- Produces: 填空答案保留 `\n`，长度上限 20000。

- [ ] **Step 1: 写失败测试**

```ts
it('keeps multi-line code answers', async () => {
  const wrapper = mountPaper()
  await wrapper.find('[data-test="fill-1"] textarea').setValue('class Solution {\n  int f() { return 1; }\n}')
  expect((wrapper.vm as any).answers[1]).toContain('\n')
})
it('caps answer length at 20000', async () => {
  const wrapper = mountPaper()
  const input = wrapper.find('[data-test="fill-1"] textarea').element as HTMLTextAreaElement
  expect(input.maxLength).toBe(20000)
})
```

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && pnpm test -- ExamPaperView`
Expected: FAIL（仍是 input，找不到 textarea）。

- [ ] **Step 3: 实现**

- 前端把 `el-input` 改为 `type="textarea"`、`:autosize="{ minRows: 4, maxRows: 16 }"`、`:maxlength="20000"`、`class="quiz-item__code"`，样式加 `font-family: ui-monospace, SFMono-Regular, Consolas, monospace`；占位文案改为“填写代码，保留缩进；交卷后由老师评分”。
- 后端 `validateAnswers` 的 `text.length() > 500` 改为 `> 20000`。

- [ ] **Step 4: 运行通过**

Run: `cd frontend && pnpm test -- ExamPaperView`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/views/oj/ExamPaperView.vue frontend/src/views/oj/__tests__/ExamPaperView.spec.ts \
        backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AnswerSheetServiceImpl.java
git commit -m "feat(oj): 填空作答改为多行代码输入"
```

---

### Task 7: 前端阅卷页与接口

**Files:**
- Modify: `frontend/src/api/types.ts`（新增阅卷类型）
- Modify: `frontend/src/api/modules/quiz.ts`（新增 5 个函数）
- Create: `frontend/src/views/oj/QuizReviewView.vue`
- Modify: `frontend/src/router/routes.ts`（`/oj/assessments/:assessmentId/quiz-review`，`roles: STAFF_ROLES`）
- Modify: `frontend/src/views/oj/AssessmentResultsView.vue`、`AssessmentDetailView.vue`（入口按钮）
- Test: `frontend/src/views/oj/__tests__/QuizReviewView.spec.ts`

**Interfaces:**
- Consumes: Task 3 后端接口。
- Produces:
  - `listQuizReview(assessmentId, query)`
  - `getQuizReviewSummary(assessmentId)`
  - `saveQuizReviewScores(assessmentId, payload, idempotencyKey)`
  - `revokeQuizReviewScore(assessmentId, sheetId, position, payload, idempotencyKey)`

- [ ] **Step 1: 写失败测试**

```ts
it('submits only edited rows', async () => {
  const wrapper = mountReview()
  await flushPromises()
  await wrapper.find('[data-test="score-88"] input').setValue('8')
  await wrapper.find('[data-test="save"]').trigger('click')
  expect(saveMock).toHaveBeenCalledWith(assessmentId, { items: [{ sheetId: '88', position: 3, score: 8, comment: '', version: 1 }] }, expect.any(String))
})
it('disables unanswered rows', async () => {
  const wrapper = mountReview()
  await flushPromises()
  expect(wrapper.find('[data-test="score-90"] input').attributes('disabled')).toBeDefined()
})
it('shows remaining count when publishing is blocked', async () => {
  const wrapper = mountReview({ pendingItems: 12 })
  await flushPromises()
  expect(wrapper.text()).toContain('还有 12 条未批')
})
```

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && pnpm test -- QuizReviewView`
Expected: FAIL，组件不存在。

- [ ] **Step 3: 实现**

页面结构按规格 §7.5：题目下拉、进度、筛选、表格（学生/作答/得分/评语）、保存本页、全部确认自动分。
- 只收集 `dirty` 行；保存时每行带 `version`；成功后用响应更新本地 `version` 与总分并刷新进度。
- “全部确认自动分”调用同一批量接口，参数为该题所有 `autoAwarded > 0 && manualScore === null` 的行（前端按 200 条分批）。
- 得分用 `el-input-number :min="0" :max="row.maxScore"`；未作答行禁用。
- 失败时 `ElMessage.error(describeError(toApiError(error)))`，409 提示“分数已被其他老师修改，请刷新”。

- [ ] **Step 4: 运行通过**

Run: `cd frontend && pnpm test -- QuizReviewView`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/api/types.ts frontend/src/api/modules/quiz.ts \
        frontend/src/views/oj/QuizReviewView.vue frontend/src/router/routes.ts \
        frontend/src/views/oj/AssessmentResultsView.vue frontend/src/views/oj/AssessmentDetailView.vue \
        frontend/src/views/oj/__tests__/QuizReviewView.spec.ts
git commit -m "feat(oj): 增加按题横向阅卷页面"
```

---

### Task 8: 成绩页发布流程与学生待公布态

**Files:**
- Modify: `frontend/src/views/oj/AssessmentResultsView.vue`
- Modify: `frontend/src/api/modules/oj.ts`（`publishAssessmentResults`）
- Modify: `frontend/src/api/types.ts`（`resultsPublished`、明细新字段）
- Test: `frontend/src/views/oj/__tests__/AssessmentResultsView.spec.ts`

**Interfaces:**
- Consumes: Task 4/5 的发布接口与响应字段。
- Produces: `publishAssessmentResults(assessmentId, payload, idempotencyKey)`。

- [ ] **Step 1: 写失败测试**

```ts
it('hides scores for students before publication', async () => {
  const wrapper = mountResults({ role: 'STUDENT', resultsPublished: false })
  await flushPromises()
  expect(wrapper.text()).toContain('成绩待公布')
  expect(wrapper.text()).not.toContain('总分')
})
it('enables publish only when grading is complete', async () => {
  const wrapper = mountResults({ pendingItems: 3 })
  expect(wrapper.find('[data-test="publish"]').attributes('disabled')).toBeDefined()
})
it('publishes after confirmation', async () => {
  const wrapper = mountResults({ pendingItems: 0 })
  await wrapper.find('[data-test="publish"]').trigger('click')
  await wrapper.find('[data-test="confirm-publish"]').trigger('click')
  expect(publishMock).toHaveBeenCalled()
})
```

- [ ] **Step 2: 运行确认失败**

Run: `cd frontend && pnpm test -- AssessmentResultsView`
Expected: FAIL。

- [ ] **Step 3: 实现**

- 教师端：顶部进度“已批 x/y”，`publish` 按钮在 `canPublish=false` 时禁用并显示“还有 N 条未批”；确认框文案强调“发布后学生可见、阅卷锁定、积分按最终分结算”；成功后按钮变只读文案。
- 明细表格：增加“自动分 / 人工分 / 评语”列，`reviewed` 行打“已复核”标签；“得分”列改为 `awarded / item.score`。
- 学生端：未发布时不请求明细、不显示总分与排名，显示“已结束 · 成绩待公布”；发布后按原逻辑展示。

- [ ] **Step 4: 运行通过**

Run: `cd frontend && pnpm test -- AssessmentResultsView`
Expected: PASS。

- [ ] **Step 5: 提交**

```bash
git add frontend/src/views/oj/AssessmentResultsView.vue frontend/src/api/modules/oj.ts \
        frontend/src/api/types.ts frontend/src/views/oj/__tests__/AssessmentResultsView.spec.ts
git commit -m "feat(oj): 成绩发布流程与学生待公布态"
```

---

### Task 9: 契约同步与整体验证

**Files:**
- Modify: `docs/api/openapi.yaml`（4 个新路径 + 新 schema）
- Modify: `backend/src/main/resources/api-contract.yaml`（同步同样内容）
- Modify: `docs/api/README.md`（接口清单与阅卷规则）
- Modify: `README.md`（功能清单加一行）

**Interfaces:**
- Consumes: Task 3/4 的路径与 DTO。

- [ ] **Step 1: 同步契约**

在两份 YAML 中新增 `GET .../quiz-review`、`GET .../quiz-review/summary`、`POST .../quiz-review/scores`、`POST .../quiz-review/scores/{sheetId}/{position}/revoke`、`POST .../publish-results`，并为 `Assessment` 增加 `resultsPublished/resultsPublishedAt`。两份文件内容必须一致（可用 diff 校验）。

- [ ] **Step 2: 全量验证**

```bash
cd backend && ./mvnw -Dtest='QuizReviewPolicyTest,QuizManualGradingSchemaIT' test
cd frontend && pnpm test && pnpm type-check && pnpm lint
```

有 Docker 的环境再跑：`cd backend && ./mvnw verify`。
Expected: 全部通过；仅本机无 Docker 时集成测试记为待 CI。

- [ ] **Step 3: 手工冒烟**

启动本地环境（见 `D:\develop\.local\innovation\README.md`），用管理员建一场 1 道填空的考核：
学生交卷 → 到点后状态 CLOSED → 学生看到“成绩待公布” → 教师批 8 分 → 发布 → 学生看到 8 分与评语 → `point_ledger` 出现 `ASSESSMENT_REWARD`。

- [ ] **Step 4: 提交**

```bash
git add docs/api/openapi.yaml backend/src/main/resources/api-contract.yaml docs/api/README.md README.md
git commit -m "docs(oj): 同步填空阅卷与成绩发布契约"
```
