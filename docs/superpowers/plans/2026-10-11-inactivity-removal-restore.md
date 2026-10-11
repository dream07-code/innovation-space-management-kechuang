# 学生日报不活跃自动移出与管理员恢复 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 取消月度评定/淘汰，实现学生连续14个完整自然日未提交正式日报后自动移出，并允许学院管理员恢复学生；历史报告和积分流水保留，但净积分清零。

**Architecture:** 用独立的上海自然日策略计算完整不活跃日，由单进程定时任务批量找出 `ACTIVE` 学生并以事务执行移出。积分清零通过给原始流水追加等额 `REVERSAL` 完成；恢复接口复用相同清零原语并开始新的在籍周期。

**Tech Stack:** Java 21、Spring Boot 4.1.1、MyBatis-Plus 3.5.17、Spring JDBC、MySQL 8.0.46、Flyway、Spring Scheduling、JUnit/Testcontainers。

**Spec:** `docs/superpowers/specs/2026-10-11-inactivity-removal-restore-design.md`

## Global Constraints

- 只在 `backend` 分支实现；不修改或合并 `main`。
- 业务日期使用 `Asia/Shanghai`；当前自然日不计入完整日。
- 只有 `ACTIVE` 的 `STUDENT` 参与自动移出；`DIRECTION_LEADER` 和 `COLLEGE_ADMIN` 不参与。
- 当前周期活跃日取 `first_submitted_at >= membership_started_at` 的最新 `SUBMITTED` 日报；草稿、周报和旧周期日报无效。
- 14个完整自然日是固定业务规则，规则版本 `INACTIVE_DAILY_REPORT_14_COMPLETE_DAYS_V1`。
- 报告和原始 `point_ledger` 不删除、不修改；清零只新增 `REVERSAL`。
- 每条原始非0流水只对应一个固定事件键的冲正；重复移出/恢复不得重复冲正。
- 自动移出撤销全部会话并递增 `app_user.auth_revision`；恢复不恢复旧会话。
- 恢复是学院管理员专用、版本受控、Idempotency-Key 幂等接口。
- 恢复保留 `joinedAt`、报告、流水、审计，重置 `membership_started_at`，净积分保持0。
- 移出后迟到 AI 结果不得增加积分。
- 月度评定、月度末位淘汰、月度规则/快照/确认/执行/任务路由全部删除；月度积分查询和排行榜保留。
- 不引入真实学生数据、密码、token、AI Key 或报告正文到 Git/日志/审计。

## Review Focus

1. `2026-10-01` 加入或最后日报为 `2026-10-10` 时，必须在第14个完整自然日结束后的下一日开始时移出，当前进行中的日期不计数。  
   Test: `InactivityPolicyTest.completeDaysUseShanghaiDateBoundaries`。
2. 恢复后的旧日报不得延长新观察期；只有 `first_submitted_at >= membership_startedAt` 的日报有效。  
   Test: `MembershipRemovalServiceIT.restoreStartsNewObservationPeriod`。
3. 清零后总分、每份报告 `awardedPoints` 和历史月份净分均为0，原始流水仍在，重复执行不产生二次冲正。  
   Test: `PointLedgerResetIT.zeroesEveryNetWithoutDeletingHistory`。
4. 自动移出后迟到评分不能重新加分；人工/旧版本状态仍优先。  
   Test: `ScoringWorkerIT.removedMemberLateScoreCannotAddPoints`。
5. 恢复接口必须拒绝学生/负责人、ACTIVE 成员和旧 version，并正确重放 Idempotency-Key。  
   Test: `MemberRestoreHttpIT.restoreIsAdminOnlyVersionedAndIdempotent`。

---

## Task 1: 自然日策略与 V5 查询索引

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/membership/InactivityPolicy.java`
- Create: `backend/src/main/resources/db/migration/V5__add_membership_activity_indexes.sql`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/membership/InactivityPolicyTest.java`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/FlywayMigrationIT.java`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/JarBoundaryIT.java`

**Interfaces:**
- Consumes: `java.time.LocalDate` 业务日期和日期型 `membershipStartedAt/lastSubmittedAt`。
- Produces: `InactivityPolicy.evaluate(LocalDate businessDate, LocalDate membershipStartedAt, LocalDate lastSubmittedAt)` 返回 `InactivityPolicy.Evaluation(LocalDate referenceDate, int completedInactiveDays, boolean removable)`。

**Rules:**
- `lastSubmittedAt == null` 时 `referenceDate=membershipStartedAt`。
- `referenceDate=max(membershipStartedAt,lastSubmittedAt)`。
- `completedInactiveDays=max(0, DAYS.between(referenceDate.plusDays(1), businessDate))`。
- `removable=completedInactiveDays>=14`。

- [x] **Step 1: Write RED tests**

Create `InactivityPolicyTest.completeDaysUseShanghaiDateBoundaries`：

```java
assertEquals(13, policy.evaluate(LocalDate.of(2026,10,15), LocalDate.of(2026,10,1), null).completedInactiveDays());
assertFalse(policy.evaluate(LocalDate.of(2026,10,15), LocalDate.of(2026,10,1), null).removable());
assertEquals(14, policy.evaluate(LocalDate.of(2026,10,16), LocalDate.of(2026,10,1), null).completedInactiveDays());
assertTrue(policy.evaluate(LocalDate.of(2026,10,16), LocalDate.of(2026,10,1), null).removable());
assertEquals(14, policy.evaluate(LocalDate.of(2026,10,25), LocalDate.of(2026,10,1), LocalDate.of(2026,10,10)).completedInactiveDays());
```

Add V5 assertions：两个索引存在，`member`/`report` 行数仍为0。

- [x] **Step 2: Run RED**

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dtest=InactivityPolicyTest test`  
Expected: compilation failure/missing policy.

- [x] **Step 3: Implement policy and migration**

Implement the exact `Evaluation` record and `evaluate` signature above。V5 只创建：

```sql
CREATE INDEX idx_report_member_activity ON report(member_id,report_type,status,first_submitted_at);
CREATE INDEX idx_member_auto_removal ON member(membership_status,membership_started_at);
```

- [x] **Step 4: Run GREEN**

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dtest=InactivityPolicyTest test` and `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=FlywayMigrationIT,JarBoundaryIT verify`  
Expected: PASS，V1–V5 可迁移，正式 JAR 只带 V1–V5。

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java backend/src/main/resources backend/src/test/java
git commit -m "feat: add inactivity policy and membership indexes"
```

---

## Task 2: 积分流水幂等冲正

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/points/PointLedgerService.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/PointLedgerResetIT.java`

**Interfaces:**
- Consumes: `point_ledger` 原始行的 `id/member_id/report_id/report_version_id/delta/business_effective_at`。
- Produces: `int reverseNonZeroEntries(long memberId, String ruleVersion, Instant recordedAt)`；返回本次新增冲正数。

**Rules:**
- 只读取 `delta<>0 AND source<>'REVERSAL'`。
- 冲正 `delta=-原delta`、`source='REVERSAL'`。
- 复用原行的 `report_id/report_version_id/business_effective_at`。
- `event_key="MEMBERSHIP_POINTS_RESET:"+memberId+":"+originalLedgerId+":"+ruleVersion`。
- `rule_version` 使用传入规则版本。
- `DuplicateKeyException` 视为已冲正，不报错、不重复写。

- [x] **Step 1: Write RED test**

`PointLedgerResetIT.zeroesEveryNetWithoutDeletingHistory` 使用真实 MySQL 创建两个报告、跨两个月的三条非0原始流水，调用两次。断言：

- 原始行数量不变。
- 成员总分为0。
- 每个 `report_id` 的 `SUM(delta)=0`。
- 每个 `business_effective_at` 月份的净分均为0。
- 第一次返回原始行数，第二次返回0。

- [x] **Step 2: Run RED**

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=PointLedgerResetIT verify`  
Expected: missing method/behavior。

- [x] **Step 3: Implement reversal primitive**

实现 `reverseNonZeroEntries(long memberId, String ruleVersion, Instant recordedAt)`；每个原始行独立事务外执行参数化插入并捕获 `DuplicateKeyException`。

- [x] **Step 4: Run GREEN**

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=PointLedgerResetIT verify`  
Expected: PASS。

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: add idempotent membership point reversal"
```

---

## Task 3: 自动移出服务与调度器

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/membership/MembershipRemovalProperties.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/membership/MembershipRemovalService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/membership/MembershipRemovalScheduler.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/MembershipRemovalServiceIT.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/membership/MembershipRemovalSchedulerTest.java`

**Interfaces:**
- Consumes: `InactivityPolicy.evaluate`、`PointLedgerService.reverseNonZeroEntries`、注入的 `Clock` 和 `PlatformTransactionManager`。
- Produces:
  - `int MembershipRemovalService.removeDueMembers()`
  - `boolean MembershipRemovalService.removeDueMember(long memberId)`
  - `void MembershipRemovalScheduler.poll()`

**Rules:**
- 配置前缀 `app.membership`：
  - `auto-removal-enabled=true`
  - `removal-check-interval-ms=3600000`
  - `removal-batch-size=500`
- 启动时校验 interval 60000–86400000、batch 1–5000。
- 候选 SQL 只选 `member.membership_status='ACTIVE' JOIN app_user role='STUDENT'`。
- `lastSubmittedAt` 只统计当前周期 `DAILY/SUBMITTED`。
- 单成员事务内条件更新；仅更新成功才冲正、撤销会话和写审计。
- 自动审计 `actor_user_id=null`、action `MEMBER_AUTO_REMOVED`、reason `TWO_WEEKS_NO_DAILY_REPORT`。
- `safe detail` 含 `reasonCode/membershipStartedAt/lastDailySubmittedAt/completedInactiveDays/thresholdDays=14/ruleVersion`，不含学号、姓名和正文。
- 调度器使用 `@Scheduled(fixedDelayString="${app.membership.removal-check-interval-ms:3600000}")`，可通过属性禁用。

- [x] **Step 1: Write RED tests**

`MembershipRemovalServiceIT` 覆盖：

- 13个完整日不移出，14个完整日后移出。
- 草稿/周报/旧周期日报不重置。
- 只移出学生，负责人和管理员保留。
- 移出后状态/时间/原因/version/auth revision/会话正确。
- 重复调用只写一条审计。
- 报告和原始流水保留，净积分0。

`MembershipRemovalSchedulerTest` 断言 disabled 不调用 service、enabled 调用一次。

- [x] **Step 2: Run RED**

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dtest=MembershipRemovalSchedulerTest test` and `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=MembershipRemovalServiceIT verify`  
Expected: missing implementation。

- [x] **Step 3: Implement properties, service, scheduler**

实现上述精确接口；移出更新使用 `WHERE id=? AND membership_status='ACTIVE'`，返回受影响行数决定是否继续。

- [x] **Step 4: Run GREEN**

Run: both targeted commands above。  
Expected: PASS。

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: auto-remove inactive students"
```

---

## Task 4: 学院管理员恢复学生

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/member/MemberService.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/member/MemberController.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/MemberRestoreHttpIT.java`

**Interfaces:**
- Consumes: `MemberService.adminAction`、`PointLedgerService.reverseNonZeroEntries`。
- Produces: `Map<String,Object> MemberService.restore(long id, Map<String,Object> request, SessionPrincipal actor)`。
- HTTP: `POST /api/v1/members/{memberId}/restore`，Header `Idempotency-Key`，body `{version:int,reason:string}`，返回 `MemberResponse`。

**Rules:**
- `reason` 长度5–1000；未知字段400。
- 仅 `COLLEGE_ADMIN`；目标仅 `REMOVED` 的 `STUDENT`。
- `version` 不匹配409 `VERSION_CONFLICT`。
- 恢复写 `ACTIVE`、`membership_started_at=now`、`removed_at/removal_reason=null`、`version+1`。
- `auth_revision+1`，撤销旧会话 `MEMBER_RESTORED`。
- 恢复时再次执行幂等积分清零，净积分为0。
- `joinedAt` 和 `app_user.status` 不变。
- 审计 action `MEMBER_RESTORED`，记录 actor/reason/version。
- 同一 Idempotency-Key 重放；不同请求复用 Key 返回409。

- [x] **Step 1: Write RED HTTP tests**

`MemberRestoreHttpIT.restoreIsAdminOnlyVersionedAndIdempotent` 覆盖管理员成功、学生/负责人403、ACTIVE 409、旧 version 409、Key 冲突409、重复成功幂等、账号 DISABLED 仍DISABLED、报告/流水保留且积分0、`membership_started_at` 改变。

- [x] **Step 2: Run RED**

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=MemberRestoreHttpIT verify`  
Expected: route不存在/行为缺失。

- [x] **Step 3: Implement restore service and route**

复用 `adminAction` 的权限、版本和幂等边界；只新增 restore 状态事务和积分清零调用。

- [x] **Step 4: Run GREEN**

Run: targeted command。  
Expected: PASS。

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: add admin student restore"
```

---

## Task 5: 移出后阻止迟到评分加分

**Files:**
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/scoring/ScoringWorker.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/ScoringWorkerIT.java`

**Interfaces:**
- Consumes: 当前任务/report/member 查询。
- Produces: 已移出成员任务转换为 `STALE`，不调用 `PointLedgerService`。

**Rules:**
- 成功回调落账前再次校验 `member.membership_status='ACTIVE'`。
- `REMOVED` 时任务 `STALE`，报告已有清零状态不被改写。
- 失败回调同样不得把已移出成员任务覆盖为 RETRYING/REVIEW_REQUIRED。

- [x] **Step 1: Write RED test**

`ScoringWorkerIT.removedMemberLateScoreCannotAddPoints`：提交报告后移出并清零，令评分任务返回3分，执行 Worker，断言任务 `STALE`、净积分仍0、报告状态不增加流水。

- [x] **Step 2: Run RED**

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=ScoringWorkerIT verify`  
Expected: 当前会增加积分。

- [x] **Step 3: Implement member-state guard**

在成功/失败落账事务中加入状态检查；保持 manual/stale 优先级。

- [x] **Step 4: Run GREEN**

Run: targeted command。  
Expected: PASS。

- [x] **Step 5: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "fix: prevent late scores after member removal"
```

---

## Task 6: API 契约、删除月度淘汰和文档同步

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Modify: `docs/requirements.md`
- Modify: `docs/api/README.md`
- Modify: `docs/api/VALIDATION.md`
- Modify: `backend/README.md`
- Modify: `docs/superpowers/specs/2026-10-11-report-scoring-points-ranking-design.md`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/ImplementedApiContractIT.java`

**Interfaces:**
- Consumes: Task 4 的 restore 路由和 Task 3 的配置语义。
- Produces: 两份契约字节一致，restore 标 `implemented`，月度淘汰路径不存在。

**Rules:**
- 删除 `/api/v1/system/rules/monthly-assessment`、`/api/v1/monthly-assessments*`、`/api/v1/tasks*` 以及仅服务于月度淘汰的 schemas/examples。
- 保留 `GET /system/rules` 设计项和 `period=MONTH` 积分查询/排行榜。
- restore 使用既有 `ReasonVersionRequest/MemberResponse`，状态从 `pending` 改 `implemented`。
- 手动 `/remove` 保持未实现且不得标 `implemented`。
- 需求文档删除“每月评定末位移出”，写入14个完整自然日自动移出和管理员恢复。
- README 写清配置、清零冲正、恢复语义和月度淘汰取消。

- [x] **Step 1: Write RED contract tests**

`ImplementedApiContractIT` 断言 restore 存在且 implemented、月度 assessment/tasks/monthly rule 路径不存在、两份契约一致、rankings `period=MONTH` 仍存在。

- [x] **Step 2: Run RED**

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=ImplementedApiContractIT verify`  
Expected: restore未标实现/月度路径仍存在。

- [x] **Step 3: Update contract and docs**

按上述规则同步 OpenAPI、运行契约、需求和运维文档；禁止写入真实账号/Key/正文。

- [x] **Step 4: Run GREEN**

Run: targeted command。  
Expected: PASS。

- [x] **Step 5: Commit**

```bash
git add docs backend
git commit -m "docs: specify inactivity removal and cancel monthly elimination"
```

---

## Task 7: 全量交付与独立审查

**Files:**
- Create: `docs/superpowers/reports/2026-10-11-inactivity-removal-restore-verification.md`
- Modify: `docs/superpowers/plans/2026-10-11-inactivity-removal-restore.md`

**Interfaces:**
- Consumes: Task 1–6 的代码、迁移、契约和文档。
- Produces: 完整验证报告、最终 `backend` 提交和远端更新。

- [x] **Step 1: Run full verification**

Run：

```bash
SPRING_PROFILES_ACTIVE=dev \
SPRING_DATASOURCE_HIKARI_JDBC_URL='jdbc:mysql://never-contact.invalid:1/decoy' \
SPRING_DATASOURCE_HIKARI_USERNAME=decoy \
SPRING_DATASOURCE_HIKARI_PASSWORD=decoy \
backend/mvnw -f backend/pom.xml -B -ntp clean verify
```

Expected: zero failures/errors/skips。

- [x] **Step 2: Run operational scans**

Run dependency tree、`git diff --check`、密钥/Token/正文扫描、V1–V5 JAR 边界和 Playwright Knife4j smoke。Expected: all PASS。

- [x] **Step 3: Independent whole-branch review**

Review `origin/main..HEAD` against the confirmed spec；fix all Critical/Important with RED→GREEN，记录 Ruling/deferred Minor。

- [x] **Step 4: Rerun after review fixes**

Repeat Step 1–2 and browser smoke after final code change。

- [ ] **Step 5: Commit and push**

Create the verification report, mark plan checkboxes, commit `test: verify inactivity removal and restore delivery`, and push `backend` only。

---

## Acceptance Matrix

| Requirement | Evidence |
| --- | --- |
| 取消月度评定/淘汰 | Task 6 contract/path removal and docs |
| 14个完整自然日边界 | Task 1 policy tests |
| 仅学生自动移出 | Task 3 service integration |
| 草稿/周报/旧日报无效 | Task 3 integration |
| 报告/原始流水保留、净积分0 | Task 2 reset integration |
| 迟到评分不加分 | Task 5 worker integration |
| 管理员恢复学生 | Task 4 HTTP integration |
| 恢复后新观察期 | Task 3/4 integration |
| 幂等、版本和权限 | Task 2/3/4 tests |
| 契约/Knife4j/正式JAR | Task 6/7 |
