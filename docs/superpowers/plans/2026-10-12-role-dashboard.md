# 角色化数据看板 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为学院管理员、方向负责人和学生分别实现安全、可测试的数据看板聚合 API，并为前端提供角色化首页契约和视觉交接。

**Architecture:** 在现有 Spring Boot 后端新增独立 `dashboard` 查询模块，复用成员范围、报告、积分、排行榜、OJ 结果和审计数据，按角色返回聚合快照。看板只读且不复制现有业务状态；前端根据三个独立 DTO 制作管理员、负责人、学生首页。

**Tech Stack:** Java 21、Spring Boot 4.1.1、MyBatis-Plus 3.5.17、MySQL 8.0.46、Flyway、JUnit/Testcontainers、Knife4j/OpenAPI。

**Spec:** `docs/superpowers/specs/2026-10-12-role-dashboard-design.md`

## Global Constraints

- 只在 `backend` 分支实现；不合并 `main`。
- 路由固定为 `GET /api/v1/dashboard/admin`、`GET /api/v1/dashboard/leader`、`GET /api/v1/dashboard/student`。
- `range` 仅支持 `7d`、`30d`；管理员默认 `30d`，负责人和学生默认 `7d`。
- 管理员只能使用 admin 看板；方向负责人只能使用 leader 看板；学生只能使用 student 看板。
- 负责人只聚合 `SessionPrincipal.managedDirectionIds()`；学生只聚合本人；SQL 层先过滤权限再计算总数。
- 排行榜、排名人数、rank 和同分跳位只统计 `ACTIVE` 学生，方向负责人和管理员不计入。
- 看板响应不得包含报告全文、代码正文、私有测试数据、模型原始输出、API Key、密码、Token、联系方式或学号。
- 看板读取不得写 `point_ledger`、修改报告/成员/OJ 状态或触发自动移出。
- 日期趋势使用 `Asia/Shanghai` 业务自然日，数据库连接仍为 UTC。
- OpenAPI 初始标记 `planned`；全部实现、契约和隐私测试通过后才改为 `implemented`。
- 不新增数据库表或迁移，除非现有索引无法满足聚合查询的验证结果。

## Review Focus

1. 负责人跨方向访问、学生访问他人数据，以及错误角色调用错误 dashboard 路由；对应 Task 2/3/4 的权限矩阵测试。
2. `7d/30d` 含首尾业务日、默认值和非法 range；对应 Task 1/2/3/4 的日期边界测试。
3. 报告完成率、积分净和、排名人数和 OJ 状态必须与明细接口一致且不产生写入；对应 Task 2/3/4 的快照一致性测试。
4. 学号、联系方式、报告正文、代码、Token 或 Provider 原始输出不得进入任何 dashboard 响应；对应 Task 5 的隐私扫描测试。
5. 管理员/负责人/学生 DTO 必须保持独立，不能用同一大而全响应泄露越权字段；对应 Task 1 的 OpenAPI schema 测试。

---

## File Structure

- `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardController.java`：三个只读 HTTP 路由和角色入口。
- `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardRange.java`：`7d/30d` 解析及业务日期边界。
- `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardQuerySupport.java`：安全分组、趋势桶、SQL 参数和 ID 展示的共享工具。
- `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardAdminService.java`：管理员聚合。
- `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardLeaderService.java`：负责人授权方向聚合。
- `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardStudentService.java`：学生本人聚合。
- `docs/api/openapi.yaml`、`backend/src/main/resources/api-contract.yaml`：三套 DTO、角色、range 和错误契约。
- `docs/frontend/2026-10-12-role-dashboard-handoff.md`：三套页面布局和前端验收。
- `backend/src/test/java/io/github/dream07code/innovationspace/integration/*Dashboard*IT.java`：契约、角色、数据、隐私集成测试。

---

### Task 1: 冻结角色看板 OpenAPI 与 DTO

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Create: `docs/frontend/2026-10-12-role-dashboard-handoff.md`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/DashboardApiContractIT.java`

**Interfaces:**
- Produces: `GET /api/v1/dashboard/admin?range=7d|30d`
- Produces: `GET /api/v1/dashboard/leader?range=7d|30d`
- Produces: `GET /api/v1/dashboard/student?range=7d|30d`
- DTOs: `AdminDashboard`, `LeaderDashboard`, `StudentDashboard`、`DashboardTrendPoint`、`DirectionDashboardRow`、`StudentDashboardRow`、`AttentionItem`、`StudentReportSummary`、`StudentAssessmentSummary`、`PointEntrySummary`。
- Response wrapper：现有 `code/message/data`，失败使用 `INVALID_REQUEST/UNAUTHORIZED/FORBIDDEN/NOT_FOUND/SERVICE_UNAVAILABLE`。

**DTO 契约:**
- `AdminDashboard` 必含 `range/overview/trends/directions/attention/recentActivity`。
- `LeaderDashboard` 必含 `range/overview/students/trends/attention/rankingPreview`。
- `StudentDashboard` 必含 `range/overview/progress/reports/assessments/points/rankingPreview`。
- 所有 ID 为 string；时间为 date-time；趋势点字段为 `date/dailySubmitted/dailyScored/pointsAdded`。
- `overview` 中至少固定字段：`activeStudents,totalStudents,directionLeaders,removedStudents,todayDailySubmitted,dailyCompletionRate,weeklyCompletionRate,pendingAi,failedAi,pendingReview,pendingOj`；角色不适用的字段不得进入该角色 DTO。
- `AttentionItem` 只允许 `type/memberId/displayName/directionName/occurredAt/status`；禁止 `studentNo/contact/content/code`。

- [ ] **Step 1: 写 RED 契约测试**

创建 `DashboardApiContractIT`，断言：

- 三条路径存在且初始 `x-implementation-status=planned`。
- `x-roles` 分别只允许 `COLLEGE_ADMIN`、`DIRECTION_LEADER`、`STUDENT`。
- 三个 DTO 的字段集合按上述契约存在，额外字段为 `additionalProperties: false`。
- `range` enum 仅为 `7d/30d`；非法值返回 `400 INVALID_REQUEST` 的契约声明。
- `DashboardTrendPoint.date` 为 date，ID 为 string。
- `AttentionItem` 不含 `studentNo/contact/content/code`。
- `docs/api/openapi.yaml` 与 `backend/src/main/resources/api-contract.yaml` 字节一致。

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=DashboardApiContractIT verify`
Expected: missing dashboard paths/DTOs.

- [ ] **Step 2: 实现契约与前端交接**

仅修改 YAML 和 `docs/frontend/2026-10-12-role-dashboard-handoff.md`；不创建 Java 路由。交接文档按 spec 第5节列明三个页面的布局、状态、响应式和图表可访问性。

- [ ] **Step 3: 运行 GREEN**

Run: `DashboardApiContractIT verify`
Expected: PASS；两份 YAML `Files.mismatch == -1`。

- [ ] **Step 4: Commit**

```bash
git add docs backend/src/main/resources backend/src/test/java
git commit -m "docs: freeze role dashboard API contract"
```

---

### Task 2: 管理员 Dashboard 聚合 API

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardRange.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardQuerySupport.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardAdminService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardController.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/AdminDashboardHttpIT.java`

**Interfaces:**
- Consumes: `SessionPrincipal`、`JdbcTemplate`、`Clock`、现有 member/report/point/OJ 表。
- Produces: `DashboardRange.parse(String value, DashboardRange defaultRange) -> DashboardRange`。
- Produces: `DashboardAdminService.summary(SessionPrincipal actor, String range) -> Map<String,Object>`。
- Produces: `DashboardController.adminDashboard(String range) -> ApiResponse<Map<String,Object>>`。

**行为:**
- `DashboardRange` 字段：`String raw`、`LocalDate fromInclusive`、`LocalDate toInclusive`、`int days`；日期以 `Asia/Shanghai` 计算。
- 管理员默认 `30d`；只接受 `7d/30d`。
- `overview` 从 `member`、`report`、`report_scoring_task`、`assessment`、`assessment_submission` 聚合，不读取报告正文或代码。
- `trends` 返回每日 `date/dailySubmitted/dailyScored/pointsAdded`；`pointsAdded` 只计算正向 `point_ledger.delta`。
- `directions` 只返回方向级安全字段，按 `activeStudents DESC,directionId ASC` 排序。
- `attention` 只返回前10条，按风险优先级：评分失败、待人工复核、待判定 OJ、14日不活跃风险。
- `recentActivity` 只返回脱敏审计摘要，最多10条。
- 不允许非管理员调用；查询只读，不调用 `point_ledger` INSERT/UPDATE。

- [ ] **Step 1: 写 RED HTTP 测试**

创建 `AdminDashboardHttpIT`，用真实 MySQL fixtures 覆盖：

- `adminDashboardReturnsRoleScopedAggregatesAndTrends`：管理员获得 `overview/trends/directions/attention/recentActivity`；`30d` 默认值正确；趋势不含报告正文；`totalStudents` 不含负责人。
- `adminDashboardRejectsInvalidRangeAndOtherRoles`：`range=99d` 为400；leader/student 调用 admin 路由为403。
- `adminDashboardDoesNotMutateBusinessData`：请求前后 `point_ledger/report/member/assessment_submission` 行数和状态不变。
- `adminDashboardDateBucketUsesBusinessCalendar`：跨 UTC 午夜 fixture 落入 `Asia/Shanghai` 正确日期。
- `adminAttentionListIsBoundedAndSafe`：超过10条风险时只返回10条，响应无 `studentNo/contact/content/code`。

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=AdminDashboardHttpIT verify`
Expected: 404/无路由或缺少 service 行为。

- [ ] **Step 2: 实现 range、查询支持和管理员服务**

`DashboardQuerySupport` 提供：
- `List<Map<String,Object>> trend(List<String> dates, JdbcTemplate jdbc, String scopeSql, List<Object> args)`；
- `String safeDisplay(String value)`；
- `Map<String,Object> idString(Object id)` 仅使用 `String.valueOf`；
- `LocalDate businessDate(Instant instant)`。

`DashboardAdminService` 使用 JDBC 聚合查询；禁止查出 `report.content_json`、`assessment_submission_code.code_text`、`password_hash`、`auth_session`、`audit_log.detail`。

- [ ] **Step 3: 运行 GREEN**

Run: `AdminDashboardHttpIT verify`
Expected: PASS；现有管理/报告/积分测试无回归。

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: add admin role dashboard"
```

---

### Task 3: 方向负责人 Dashboard 聚合 API

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardLeaderService.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardController.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/LeaderDashboardHttpIT.java`

**Interfaces:**
- Consumes: `DashboardRange`、`DashboardQuerySupport`、`SessionPrincipal.managedDirectionIds()`。
- Produces: `DashboardLeaderService.summary(SessionPrincipal actor, String range) -> Map<String,Object>`。

**行为:**
- 默认 `7d`；只接受 `7d/30d`。
- `overview` 仅统计授权方向的 `ACTIVE` 学生和报告、积分、OJ 数据。
- `students` 每行只含 `memberId/displayName/membershipStatus/lastDailyAt/lastWeeklyAt/points/directionRank`。
- `rankingPreview` 只含授权方向 `ACTIVE` 学生前10名；方向负责人本人永不进入。
- 无授权方向返回结构完整的空 dashboard，不返回其他方向的总数或列表。
- 跨方向 fixtures 不得进入 `total`、`items`、趋势、待办或排名。

- [ ] **Step 1: 写 RED HTTP 测试**

创建 `LeaderDashboardHttpIT`，断言：

- 授权方向聚合与管理员对应数据一致。
- leader A 不能获得 leader B 的方向数据；跨方向总数为0。
- `students` 响应不含 `studentNo/contact/content/code`。
- `rankingPreview` 只含学生，leader fixture 不出现。
- `range=30d` 和非法 range 分别正确。
- admin/student 调用 leader 路由为403。

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=LeaderDashboardHttpIT verify`
Expected: 无 leader 路由。

- [ ] **Step 2: 实现负责人聚合**

在 `DashboardController` 增加 `leaderDashboard(String range)`；`DashboardLeaderService` 仅接受非空 `managedDirectionIds`，所有列表查询先使用 `a.direction_id IN (...)` 过滤。

- [ ] **Step 3: 运行 GREEN**

Run: `LeaderDashboardHttpIT verify`
Expected: PASS；负责人既有报告/积分/权限测试无回归。

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: add leader role dashboard"
```

---

### Task 4: 学生 Dashboard 聚合 API

**Files:**
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardStudentService.java`
- Modify: `backend/src/main/java/io/github/dream07code/innovationspace/dashboard/DashboardController.java`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/StudentDashboardHttpIT.java`

**Interfaces:**
- Consumes: `DashboardRange`、`DashboardQuerySupport`、`SessionPrincipal.memberId()`。
- Produces: `DashboardStudentService.summary(SessionPrincipal actor, String range) -> Map<String,Object>`。

**行为:**
- 默认 `7d`；只接受 `7d/30d`。
- `overview` 含本人 `points/overallRank/directionRank` 和报告状态；负责人/管理员不能调用。
- `progress` 含本人近7/30天积分、日报/周报提交度和安全的不活跃距离，不在前端自行计算移出。
- `reports/assessments/points` 只含本人摘要；不返回代码、报告全文、测试数据或 Provider 输出。
- `rankingPreview` 只返回本人排名和排名统计，不返回其他学生详情。
- 已移出学生由现有会话策略拒绝登录；已登录的非 `ACTIVE` 状态返回安全的空状态，不伪造排名。

- [ ] **Step 1: 写 RED HTTP 测试**

创建 `StudentDashboardHttpIT`，断言：

- 本人摘要与 `/points/me`、`/rankings/me`、报告详情一致。
- 学生不能调用 admin/leader 路由；其他学生的数据不会出现在响应。
- 7d/30d 默认值和非法值正确。
- 响应不含 `studentNo/contact/content/code/privateTestData/rawProviderResponse`。
- 看板请求前后 `point_ledger`、报告状态和成员状态不变。

Run: `backend/mvnw -f backend/pom.xml -B -ntp -Dit.test=StudentDashboardHttpIT verify`
Expected: 无 student 路由。

- [ ] **Step 2: 实现学生聚合**

在 `DashboardController` 增加 `studentDashboard(String range)`；`DashboardStudentService` 强制 `actor.memberId()`，不做按请求参数选人。

- [ ] **Step 3: 运行 GREEN**

Run: `StudentDashboardHttpIT verify`
Expected: PASS；学生报告、积分、排名测试无回归。

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java backend/src/test/java
git commit -m "feat: add student role dashboard"
```

---

### Task 5: Dashboard 隐私、权限和契约收口

**Files:**
- Modify: `docs/api/openapi.yaml`
- Modify: `backend/src/main/resources/api-contract.yaml`
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/DashboardPrivacyIT.java`

**Interfaces:**
- Consumes: Task 1 DTO、Task 2/3/4 服务。
- Produces: OpenAPI 中 dashboard 操作 `x-implementation-status: implemented`。

- [ ] **Step 1: 写 RED 隐私/契约测试**

`DashboardPrivacyIT` 断言三个 dashboard 的完整响应树不含：

`studentNo`、`contact`、`content`、`code`、`codeText`、`codeLocator`、`privateTestData`、`rawProviderResponse`、`apiToken`、`password`。

同时断言：

- admin/leader/student 角色矩阵。
- `point_ledger` 无新增。
- 所有 ID 为字符串。
- `DashboardApiContractIT` 和 `ImplementedApiContractIT` 通过。

- [ ] **Step 2: 更新实现状态和测试夹具**

只在测试通过后，将三个 dashboard 操作从 `planned` 改为 `implemented`；保持请求/响应 DTO 与前端交接一致。同步两份 YAML。

- [ ] **Step 3: 运行 GREEN**

Run: `DashboardPrivacyIT,DashboardApiContractIT,ImplementedApiContractIT verify`
Expected: PASS，两份 YAML 字节一致。

- [ ] **Step 4: Commit**

```bash
git add docs backend/src/main/resources backend/src/test/java
git commit -m "test: secure role dashboard contracts"
```

---

### Task 6: 全量验证和交付

**Files:**
- Create: `docs/superpowers/reports/2026-10-12-role-dashboard-verification.md`
- Modify: `backend/README.md`

**Interfaces:**
- Consumes: Task 1-5。
- Produces: role dashboard verification report and frontend handoff status。

- [ ] **Step 1: 全量验证**

Run：`backend/mvnw -f backend/pom.xml -B -ntp clean verify`。
同时执行 OpenAPI 规范/示例校验、两份契约副本比较、依赖树、tracked 文件密钥扫描、Knife4j 浏览器冒烟和 `git diff --check`。记录真实测试计数和产物。

- [ ] **Step 2: 独立审查**

审查角色隔离、聚合 SQL、日期边界、排名排除负责人、只读保证、隐私字段、图表 DTO 稳定性；Critical/Important 必须 RED→GREEN 修复。

- [ ] **Step 3: 更新交付文档**

`backend/README.md` 增加三个 dashboard 路由、默认 range、角色权限、只读边界和前端页面入口。

- [ ] **Step 4: Commit**

```bash
git add docs backend/README.md
git commit -m "docs: verify role dashboards delivery"
```

---

## Acceptance Matrix

| 要求 | 证据 |
| --- | --- |
| 三个角色分别看板 | `AdminDashboardHttpIT`, `LeaderDashboardHttpIT`, `StudentDashboardHttpIT` |
| 管理员含 OJ 数据 | admin `overview/attention` fixture |
| 默认时间范围 | admin `30d`，leader/student `7d` |
| 风险列表前10条 | `adminAttentionListIsBoundedAndSafe` |
| 排行榜排除负责人 | leader/admin/student ranking tests |
| 数据聚合不写业务表 | mutation guard tests |
| 隐私边界 | `DashboardPrivacyIT` |
| 前端可独立开发 | OpenAPI DTO + `docs/frontend/2026-10-12-role-dashboard-handoff.md` |
