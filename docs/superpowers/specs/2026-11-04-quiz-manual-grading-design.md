# 考核填空题人工阅卷设计

日期：2026-11-04
状态：待评审
适用代码：`innovation-space-management`（main）

## 1. 背景与意图

AI 全栈方向考核约 50 人同时在线。Java 程序题由本机/Judge0 编译执行，单次判题的内存峰值远超 2 核 2G 服务器的余量，50 人并发会直接打爆内存。项目负责人决定：本期把原本的 Java 程序题改为“填空”形式出题，学生直接填写 Java 代码文本，交卷后由方向负责人/学院管理员人工给分。这样判题开销归零，不用换服务器。

现有填空题是“交卷即自动判分、答案精确匹配、对即满分错即 0 分”，且明确把“客观题人工批阅”列为非目标。本设计要推翻该非目标，但只针对填空题，程序题判题与人工复查链路保持原样。

## 2. 目标

- 学生在填空作答框内可以输入多行 Java 代码，换行与缩进原样保存。
- 交卷时仍执行自动判分，自动分作为参考值与兜底，不阻塞交卷。
- 考核结束后学生只能看到“已结束、成绩待公布”，不再返回分数与明细。
- 方向负责人/学院管理员可以逐题看到全部学生的作答，给出 0 到该题分值的分数并可写评语。
- 全部填空阅完后，由方向负责人/学院管理员**手动发布成绩**，学生此时才能看到分数与评语。
- 人工分**完全替代**该题的自动分（不是相加）；未人工调整的题继续沿用自动分。
- 修改后立即重算该答卷总分，成绩列表、个人成绩、导出同步生效。
- 积分奖励改为在**发布成绩时**按最终总分结算，避免按自动分错发。
- 全过程留痕：原自动分、人工分、批阅人、批阅时间、评语、修改前后值、发布时间与发布人。

## 3. 非目标

- 不改程序题（`assessment_submission`）的判题、复查与计分。
- 不做 AI 评分、不做代码相似度查重、不做自动语法检查。
- 不做多空填空、不做填空部分自动给分（多选漏选等规则不变）。
- 不做学生申诉/复核申请流程（本期人工阅卷由教师侧发起）。
- 不把阅卷结果回写题库，不做代码查重。
- 阅卷只覆盖 `FILL_BLANK`；单选、多选、判断仍自动判分为终判，不出现在阅卷列表里（请求其他题型返回 400）。
- 发布成绩是单向操作：发布后阅卷锁定、不提供撤回；确需纠错时由管理员用现有“手工加分”接口补偿，并在线下说明。
- 不做成绩复核申诉、不做多次发布、不做发布定时任务。

## 4. 约束

- 服务器 2 核 2G，同时在线约 50 人：新增能力不能引入额外常驻进程或高内存操作。人工阅卷只是文本读写，符合约束。
- 阅卷期间数据库压力集中在分页查询与批量更新，单次批量上限 200 条，避免长事务。
- 现有技术栈：Spring Boot 4.1.1 + JdbcTemplate + Flyway + Vue 3 + Element Plus，不引入新依赖。

## 5. 方案与取舍

| 方案 | 做法 | 取舍 |
| --- | --- | --- |
| A 逐份答卷批阅 | 打开某学生答卷，逐题填分 | 改动最小，但 50 份来回翻页、同一题评分尺度漂移，实际不可用 |
| **B 按题目横向批阅（已选）** | 选考核 → 选题目 → 一屏列出全部学生该题作答，逐行给分并批量保存 | 阅卷效率最高，评分尺度统一，便于发现雷同；需要新接口和新页面 |
| C 保留程序题、判题排队限流 | 判题并发压到 1、限制 JVM 内存 | 免除阅卷，但 50×N 串行耗时十几分钟，编译峰值内存仍可能 OOM，考试失败风险高 |

方案 B 的补充口径（本次确认）：

- **所有填空题仍自动判分**，保留现有 `QuizGrader` 行为与“交卷即有分”的状态机，历史数据零迁移成本。
- 自动判分对代码答案几乎必然判错（精确匹配），因此它主要起“参考 + 兜底”作用；若同场还有标准短答案填空，自动判对的可以一键确认，不必逐条重打，这才是真正省工作量的地方。
- 人工分一旦写入，该题 `awarded` 以人工分为准，自动分只保留在 `auto_awarded` 字段供追溯，不参与总分。

## 6. 现状（改动涉及的代码）

- 判分：`backend/.../assessment/QuizGrader.java`，`FILL_BLANK` 走 `matchesAlternative`，`trim` 后区分大小写全等。
- 落库：`service/impl/AnswerSheetServiceImpl.java#gradeAndStore`，只给已作答的题写 `assessment_answer_sheet_item(position, question_id, answer, correct, awarded)`，总分写 `assessment_answer_sheet.total_score`。
- 明细读取：同文件 `detailItems()`，已向教师返回 `correctAnswer`。
- 成绩：`AssessmentResultServiceImpl#memberScores` 直接读 `sh.total_score`。
- 前端试卷：`views/oj/ExamPaperView.vue`，填空题是 360px 单行 `el-input`。
- 成绩页：`views/oj/AssessmentResultsView.vue`，弹窗展示答题卡明细。
- 权限参考：`AssessmentReviewServiceImpl#requireReviewer / requireVisible`（管理员或该方向负责人）。

## 7. 设计

### 7.1 数据模型（Flyway V16）

`assessment` 增加成绩发布标记：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `results_published_at` | DATETIME(6) NULL | 成绩发布时间；NULL 表示未发布 |
| `results_published_by` | BIGINT NULL | 发布人 `app_user.id`，外键 |

`assessment_answer_sheet_item` 增加列：

| 列 | 类型 | 说明 |
| --- | --- | --- |
| `auto_awarded` | INT NOT NULL DEFAULT 0 | 交卷时自动判分结果，只读留痕 |
| `manual_score` | INT NULL | 人工评分；NULL 表示未人工调整 |
| `review_comment` | VARCHAR(500) NULL | 阅卷评语，可空 |
| `reviewed_by` | BIGINT NULL | 批阅人 `app_user.id`，外键 |
| `reviewed_at` | DATETIME(6) NULL | 批阅时间 |
| `version` | BIGINT NOT NULL DEFAULT 1 | 乐观锁，防两人同时改同一题 |

迁移时 `UPDATE assessment_answer_sheet_item SET auto_awarded = awarded`，保证历史答卷的自动分可追溯。`awarded` 语义改为“当前生效得分”，未人工调整时等于 `auto_awarded`。

校验约束：

- `manual_score` 为 NULL 或落在 `0..assessment_item.score`（业务层校验，DB 只保证 >= 0）。
- `reviewed_by/reviewed_at/manual_score` 三者要么同时为空，要么同时有值。

不新增表，不加触发器；总分仍是 `assessment_answer_sheet.total_score`，由服务层重算。

### 7.2 判分与覆盖语义

1. 交卷：`gradeAndStore` 照旧判分，写入 `awarded = auto_awarded = 自动分`，`manual_*` 为 NULL。
2. 人工评分：校验分值范围与版本号 → `manual_score=?`、`awarded=?`、`reviewed_by/at`、`version+1`。
3. 重算总分：`total_score = SUM(awarded)`（未作答不落行，按 0 计），与当前口径一致。
4. 人工分覆盖自动分，不叠加；把 `manual_score` 改回 NULL 视为撤销人工评分，`awarded` 回退到 `auto_awarded`（通过“撤销”操作暴露，而不是传空值）。
5. 程序题不受影响。

### 7.3 状态与权限

- 时间到即“结束”：现有 30 秒扫描任务已会在 `end_at` 后自动交卷草稿；本设计在同一任务里把 `PUBLISHED` 且 `end_at < now` 的考核自动置为 `CLOSED`（不结算积分）。这样“时间到 = 不能作答”，不依赖教师手动点关闭。
- 阅卷开放条件：考核 `CLOSED` 或 `ARCHIVED`。`DRAFT/PUBLISHED` 期返回 409 `INVALID_STATE`，避免考试中泄题或提前改分。
- 发布成绩条件：`CLOSED` 且阅卷完成（每张 `SUBMITTED` 答题卡的所有 `FILL_BLANK` 条目都有 `manual_score`），否则 409。`ARCHIVED` 不允许发布。
- 可阅卷/可发布角色：`COLLEGE_ADMIN`；或 `DIRECTION_LEADER` 且 `managedDirectionIds` 包含考核的 `direction_id`。学生一律 404。
- 发布后：阅卷接口与发布接口都拒绝（409），成绩只读。

### 7.4 接口

**1）阅卷列表（按题横向）**

```http
GET /api/v1/assessments/{assessmentId}/quiz-review?questionId={quizQuestionId}&filter=all|pending|auto_zero|manual&page=1&size=50
```

`questionId` 必须是本场考核中的 `QUIZ` 条目且题型为 `FILL_BLANK`，否则 400；非本场条目 404。

返回：

```json
{
  "assessment": {"id":"12","title":"AI 全栈 11 月月考","status":"CLOSED","directionId":"1"},
  "question": {"position":3,"questionId":"57","stem":"...","score":10,
               "referenceAnswers":["..."],"analysis":"..."},
  "progress": {"graded":32,"total":50},
  "items": [
    {"sheetId":"88","memberId":"5","memberName":"张三","studentNo":"2026...",
     "answer":"public int sum(...){...}","answered":true,
     "autoAwarded":0,"awarded":8,"manualScore":8,"reviewed":true,
     "comment":"边界没处理","version":2}
  ],
  "page":1,"size":50,"total":50
}
```

- 行以“已交卷的答题卡”为基准左连明细，未作答也出现在列表里（`answered=false`，不可编辑）。
- `filter=pending` 指 `manual_score IS NULL`；`auto_zero` 指 `auto_awarded=0` 且未人工评分，便于优先处理。
- 列表不返回其他题答案，避免一次拉全卷。

**2）批量评分**

```http
POST /api/v1/assessments/{assessmentId}/quiz-review/scores
Idempotency-Key: <uuid>

{"items":[{"sheetId":"88","position":3,"score":8,"comment":"边界没处理","version":2}]}
```

- 单次最多 200 条；整体一个事务；任一条版本冲突返回 409 `VERSION_CONFLICT`，不partial提交。
- 成功返回每条的新 `version`、`awarded` 与所属答卷的新 `totalScore`。
- 同一请求带同一 `Idempotency-Key` 重放返回上次结果，复用现有 `IdempotencyService`。

**3）撤销人工评分（可选但建议）**

```http
POST /api/v1/assessments/{assessmentId}/quiz-review/scores/{sheetId}/{position}/revoke
Idempotency-Key: <uuid>

{"version":2}
```

`manual_score` 置 NULL、`awarded` 回退 `auto_awarded`、重算总分、写审计。用于误判纠错。必须带当前 `version`，冲突返回 409，避免两名教师同时操作时互相覆盖。

**4）发布成绩**

```http
POST /api/v1/assessments/{assessmentId}/publish-results
Idempotency-Key: <uuid>

{"version":7}
```

- 前置：`status=CLOSED`、`results_published_at IS NULL`、阅卷完成（不存在 `manual_score IS NULL` 的 `FILL_BLANK` 明细对应已交卷答题卡）、`version` 匹配。
- 效果：写 `results_published_at/results_published_by`、`version+1`；若 `points_enabled`，在同一事务提交后调用积分结算（幂等事件键不变）；写审计 `ASSESSMENT_RESULTS_PUBLISHED`。
- 返回 `{publishedAt, publisher, settled:true|false}`。重复发布返回 409。

**5）阅卷状态（供成绩页显示进度）**

```http
GET /api/v1/assessments/{assessmentId}/quiz-review/summary
```

返回 `{totalItems, gradedItems, pendingItems, submittedSheets, resultsPublished, canPublish, pointsEnabled}`，用于教师端按钮状态与学生端“待公布”判断（学生端只返回 `resultsPublished`，避免泄露进度）。

### 7.5 前端

**试卷页（`ExamPaperView.vue`）**

- `FILL_BLANK` 由单行 `el-input` 改为 `el-input type="textarea"`：`autosize` 最小 4 行、最大 16 行，等宽字体，`maxlength=20000`，保留换行与缩进。
- 占位提示改为“填写代码，保留缩进；交卷后由老师评分”。
- 富文本/粘贴不做拦截，仅做长度校验；保存草稿逻辑不变（答案仍是字符串）。
- 后端 `AnswerSheetServiceImpl#validateAnswers` 对 `FILL_BLANK` 的 `text.length() > 500` 校验同步放宽到 20000，与前端 `maxlength` 一致；空答案仍拒绝。换行在 JSON 字符串中按 `\n` 原样存储，判分与阅卷读到的都是原始文本。

**新增阅卷页（`views/oj/QuizReviewView.vue`，路由 `/oj/assessments/:assessmentId/quiz-review`）**

```
考核：AI 全栈 11 月月考        [第 3 题 · 填空 · 10 分]   已批 32/50
筛选：[未批 ▾]   [保存本页]  [全部确认自动分]
┌────┬────────┬──────────────────────────────┬──────┬────────┐
│ #  │ 学生   │ 作答（展开查看代码）          │ 得分 │ 评语   │
├────┼────────┼──────────────────────────────┼──────┼────────┤
│ 1  │ 张三   │ public int sum(...)…         │ [ 8 ]│ 边界…  │
│ 2  │ 李四   │ （未作答）                    │  —   │        │
└────┴────────┴──────────────────────────────┴──────┴────────┘
```

- 顶部题目下拉框列出本场所有 `QUIZ` 条目，并显示该题满分与批改进度。
- 得分列用 `el-input-number`，范围 `0..score`；评语列 `maxlength=500`。
- 只提交改动过的行；“保存本页”走批量接口，成功后刷新进度与总分。
- “全部确认自动分”只处理 `auto_awarded > 0` 且 `manual_score IS NULL` 的行：把它们写成 `manual_score = auto_awarded`，从而计入进度并移出“未批”队列；自动 0 分的行绝不确认。按题覆盖全部学生（不分页），内部按 200 条分批，逐条写审计。
- 提供“只看未批/自动判 0 分/已调整”，默认“未批”。
- 入口：`AssessmentResultsView` 与考核详情页增加“阅卷”按钮，仅 `CLOSED/ARCHIVED` 且角色可见时显示。

**成绩页**

- 答题卡明细弹窗增加“自动分 / 人工分 / 评语”三列，人工改过的行打“已复核”标记。
- 教师页顶部显示阅卷进度与“发布成绩”按钮：未阅完时禁用并提示“还有 N 条未批”；点击弹确认框，提示“发布后学生可见、阅卷锁定、积分按最终分结算”。
- 发布后按钮变为“成绩已于 x 月 x 日 xx:xx 发布”，不再可点。
- 学生端：成绩未发布时，考核卡片与成绩页显示“已结束 · 成绩待公布”，不显示总分、排名与逐题明细；发布后按现有成绩页展示，附评语。

### 7.6 并发、幂等与审计

- 行级乐观锁（`version`），批量保存遇冲突整体回滚并提示刷新。
- 同一份答卷的总分重算在该事务内完成，避免与另一题的批量保存交叉覆盖；重算用 `SELECT ... FOR UPDATE` 锁答题卡行。
- 每条改动写 `assessment_audit`：`action=QUIZ_ITEM_MANUAL_GRADED`，`resource_type=ASSESSMENT_ANSWER_SHEET_ITEM`，`resource_id=sheetId`，`detail` 记录 `{position, autoAwarded, beforeAwarded, manualScore, version}`，`summary` 含学生与题目摘要。撤销写 `QUIZ_ITEM_MANUAL_REVOKED`。
- 快照式：不删除历史，只保留最后一次人工结果 + 审计流水。

### 7.7 成绩与导出

- `memberScores`、个人成绩、成绩导出继续读 `total_score`，无需改 SQL。
- `detailItems` 拆成教师视图与学生视图：教师额外拿到 `autoAwarded/manualScore/reviewed/reviewComment`；学生只拿 `awarded`、满分、`reviewComment`，看不到自动分与人工分的拆分，避免“自动 0 / 人工 8”造成的误解。
- `correct` 保留交卷时的自动判定，不因人工改分而改写；前端展示改为“得分/满分”，有人工评分时加“已复核”标记，不再用对/错二元标签表达部分分。
- 若考核已 `ARCHIVED`，成绩只读，不允许再改分（409）。

### 7.8 积分奖励结算

`points_enabled=true` 的考核，积分结算从“关闭时”移到“发布成绩时”，按发布时的最终总分（`final_score` + `total_score`）计算“本人分 ÷ 全场最高分 × 20”，沿用原事件键 `ASSESSMENT_REWARD:{assessmentId}:{memberId}` 保证只发一次。关闭流程（含自动关闭）不再调用 `settle`。

这样不会出现“按自动分 0 分发了积分、之后成绩改成 8 分”的错位。若全场最高分为 0 或未启用积分，跳过结算。

### 7.9 学生可见性与成绩公布

- 发布前：`mySheet` 返回 `{status:'SUBMITTED', resultsPublished:false, totalScore:null, items:null}`；`memberScores` 对学生不返回 `sheetScore` 与总分，页面显示“成绩待公布”。教师端不受影响，始终可见。
- 发布后：恢复现有揭示逻辑，返回总分、逐题得分（`awarded`）、满分与 `reviewComment`，不返回 `autoAwarded/manualScore`。
- 未交卷的学生不参与发布判定，成绩页显示“未交卷”。
- 发布是单向的：发布后阅卷与发布接口都返回 409；纠错走管理员手工加分并线下说明。



## 8. 兼容与迁移

- V16 只加列并回填，不删除、不改类型；旧答卷 `auto_awarded = awarded`，`manual_score IS NULL`，行为与现在完全一致。
- `assessment.results_published_at` 默认 NULL，等同于“旧考核都未发布”；对已结束的历史考核，教师需手动点一次“发布成绩”才会对学生显示（也可在迁移里把已 `ARCHIVED` 的考核回填为已发布，避免历史数据突然不可见）。本设计选择**回填已 `ARCHIVED` 的考核为已发布**，`CLOSED` 的保持待发布。
- 积分结算从关闭移到发布：对尚未发布的存量 `points_enabled` 考核，发布时会补发一次，事件键幂等不会重复。
- 契约需同步两份：`docs/api/openapi.yaml` 与 `backend/src/main/resources/api-contract.yaml`，并在 README 的接口清单补一行。
- 现有“关闭时结算”的集成测试需改为“发布时结算”。

## 9. 测试

后端：

- 单测：人工分覆盖自动分、撤销回退、总分重算、分值越界拒绝、未作答不可评分、阅卷完成判定。
- 集成：`PUBLISHED` 状态拒绝阅卷（409）、方向负责人越权（404）、管理员与授权负责人成功、版本冲突回滚、批量幂等重放、审计落库。
- 发布：阅卷未完成时发布 409；发布成功后学生可见、教师端锁定；重复发布 409；`points_enabled` 考核在发布时结算且只发一次，关闭时不再结算；发布后改分 409。
- 自动关闭：`end_at` 过后状态转 `CLOSED`、草稿自动交卷、学生只读且看不到成绩。
- 迁移：V16 后历史答卷 `auto_awarded` 回填正确，`ARCHIVED` 考核回填为已发布。

前端：

- 试卷页多行输入保留换行、草稿保存后回填一致、长度上限提示。
- 阅卷页：只提交改动行、得分范围校验、保存后进度刷新、未作答行不可编辑。
- 发布流程：未阅完按钮禁用并显示剩余条数；发布后二次确认、按钮变只读文案。
- 学生端：发布前显示“成绩待公布”且无分数，发布后显示得分与评语。


## 10. 运维建议（2 核 2G）

- 后端 JVM 固定 `-Xmx512m -Xss512k`，MySQL `innodb_buffer_pool_size=256M`，连接池维持 5。
- 考试期间不开启报告 AI 评分 Worker（`AI_WORKER_SCHEDULING_ENABLED=false`），避免与考试请求抢 CPU。
- 50 人同时交卷是短时文本写入，无判题负载；建议 `server.tomcat.threads.max=50` 左右即可。

## 11. 风险

- 人工阅卷若在考试结束后才开放，教师需在成绩公布前完成 50×N 条评分；建议按题批阅并在考核结束当天完成。
- 代码答案长度上限 20000 字符，超长会截断提示；需在题干里说明。
- 若未来恢复程序题判题，本设计不冲突：填空题人工分与程序题复查相互独立。
- 发布单向且锁定阅卷，若阅卷出错只能在发布前发现；建议发布前先抽查 2–3 份，发布后用管理员手工加分兜底。
- 自动关闭由 30 秒扫描任务触发，最长有 30 秒延迟；考试结束瞬间学生可能仍能提交一次，现有窗口校验（`end_at`）已在此前拦截，不受影响。
