# 站内公告 · 成绩导出 · 登录态保持 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付三项独立功能——两级作用域站内公告（学院级/方向级）、考核成绩导出 Excel、登录态保持（刷新不掉线）。

**Architecture:** 公告为全栈新模块（V14 迁移 + AnnouncementService JdbcTemplate 风格 + 契约 + 前端横幅/管理页）；导出纯前端（SheetJS 拉全量 member-scores 生成 xlsx）；登录态复用既有 401 刷新链路，仅把 refreshToken 持久化到 sessionStorage 并在启动时静默恢复。

**Tech Stack:** Spring Boot 4 / JdbcTemplate / Flyway / Axios + Vue 3 + Element Plus + xlsx@0.18.5 / vitest / Testcontainers MySQL。

**Spec:** `docs/superpowers/specs/2026-10-25-session-export-announcements-design.md`

## Global Constraints

- 契约文件 `backend/src/main/resources/api-contract.yaml` 与 `docs/api/openapi.yaml` 必须字节一致（ImplementedApiContractIT 强制）；改契约后 `npm run gen:api` 重新生成 types.gen.ts。
- 幂等键必须 canonical UUID（IdempotencyService 400 校验）；POST 写操作带 `Idempotency-Key`。
- token 禁止写 localStorage（契约 §3.2）；本迭代唯一例外是 sessionStorage 存 refreshToken，须同步修订契约文档。
- 后端测试用 Testcontainers；`./mvnw verify` 串行跑，禁止并行 maven。
- 迁移断言：FlywayMigrationIT、ReportPointsMigrationIT 的 13 → 14。
- 前端 npm 操作需要 `--legacy-peer-deps` 时必须带。

## Review Focus

1. **负责人越权方向公告**：负责人对非所辖方向的创建/撤回/查看——期望 403 或 404（不可枚举），Task A1 测试钉住。
2. **学生可见性边界**：学生绝不能看到其他方向公告或已撤回公告——Task A1 测试钉住。
3. **静默恢复失败路径**：refreshToken 过期/被重用检测命中时必须静默回到匿名态并清 sessionStorage，不得白屏或循环刷新——Task C 测试钉住。
4. **导出大数据分页**：member-scores 单页上限 200，导出必须循环到 total，漏页=成绩单缺人——Task B 测试钉住。
5. **横幅关闭持久化**：关闭记录按公告 id 存 localStorage，撤回旧公告发布新公告后必须重新弹出——Task A2 测试钉住。

---

### Task A1: 公告后端（迁移 + 服务 + 接口 + IT）

**Files:**
- Create: `backend/src/main/resources/db/migration/V14__create_announcements.sql`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/controller/AnnouncementController.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/service/AnnouncementService.java`
- Create: `backend/src/main/java/io/github/dream07code/innovationspace/service/impl/AnnouncementServiceImpl.java`
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/FlywayMigrationIT.java`（13→14）
- Modify: `backend/src/test/java/io/github/dream07code/innovationspace/integration/ReportPointsMigrationIT.java`（13→14）
- Test: `backend/src/test/java/io/github/dream07code/innovationspace/integration/AnnouncementHttpIT.java`

**Interfaces:**
- Produces: `GET /api/v1/announcements` → `{items:[{id,title,content,scope:'COLLEGE'|'DIRECTION',directionId?,directionName?,createdAt}], total}`；`POST /api/v1/announcements`（幂等键，body `{title,content,directionId?}`，201）；`POST /api/v1/announcements/{id}/withdraw`（幂等键，200）。前端 Task A2 按此消费。

- [ ] **Step 1: V14 迁移**

```sql
CREATE TABLE announcement (
  id BIGINT NOT NULL AUTO_INCREMENT,
  title VARCHAR(120) NOT NULL,
  content VARCHAR(2000) NOT NULL,
  direction_id BIGINT NULL,
  created_by BIGINT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  withdrawn_at DATETIME(6) NULL,
  PRIMARY KEY (id),
  CONSTRAINT fk_announcement_direction FOREIGN KEY (direction_id) REFERENCES direction (id),
  CONSTRAINT fk_announcement_creator FOREIGN KEY (created_by) REFERENCES app_user (id)
);
```

- [ ] **Step 2: 两处迁移数断言 13→14**

- [ ] **Step 3: 写 AnnouncementHttpIT 失败测试**（参照 MemberAdminActionsHttpIT 夹具风格：seedMember/seed admin/sessions.issue/send helper）。用例：
  - `adminPublishesCollegeAndDirectionAnnouncements`：管理员发学院级（无 directionId）与方向级（directionId=1）；学生列表只见学院级+方向1 的；响应含 scope/directionName；重复发不同内容不同幂等键各自成条。
  - `leaderPublishesOwnDirectionOnlyAndCannotSeeOthers`：负责人（scope direction 1）发 directionId=1 成功；发 directionId=2 → 403；不带 directionId → 400；学生2（direction 2）列表不含该公告。
  - `withdrawIsIdempotentAndPermissionChecked`：管理员撤回任意 → 200；重复同键重放 200 且结果一致；负责人撤回本方向 → 200、撤回他人方向 → 404；撤回后 GET 不再返回。
  - `validationAndRoleGates`：学生创建 403；title 空或 >120、content 空或 >2000 → 400；directionId 不存在 → 404。
- [ ] **Step 4: 运行确认失败** `./mvnw -q verify -Dsurefire.failIfNoSpecifiedTests=false -Dtest=NoSuchTest -Dit.test='AnnouncementHttpIT'` → 编译失败（Controller 不存在）
- [ ] **Step 5: 实现 Controller/Service/Impl**。要点：可见性查询 `WHERE withdrawn_at IS NULL AND (direction_id IS NULL OR direction_id IN (...))`，管理员无过滤；学生可见集合 = `member.direction_id`，负责人 = `leader_direction_scope`；创建走 IdempotencyService + TransactionTemplate（参照 AssessmentProblemServiceImpl.create 模式）；审计 action `ANNOUNCEMENT_CREATED`/`ANNOUNCEMENT_WITHDRAWN`（resource_type `ANNOUNCEMENT`）。
- [ ] **Step 6: 运行 IT 全绿**
- [ ] **Step 7: Commit** `feat(announcement): 两级公告后端（学院级/方向级）`

### Task A2: 公告契约 + 前端

**Files:**
- Modify: `backend/src/main/resources/api-contract.yaml` + `docs/api/openapi.yaml`（同步）
- Modify: `frontend/src/api/types.gen.ts`（gen:api 生成）
- Create: `frontend/src/api/modules/announcements.ts`
- Modify: `frontend/src/layouts/DefaultLayout.vue`（顶部横幅）
- Create: `frontend/src/views/admin/AnnouncementsManageView.vue`
- Modify: `frontend/src/router/routes.ts`、`frontend/src/config/menu.ts`
- Test: `frontend/src/views/admin/__tests__/AnnouncementsManageView.spec.ts`、`frontend/src/layouts/__tests__/backNavigation.spec.ts` 同目录新增 banner 断言（或独立 DefaultLayout spec）

**Interfaces:**
- Consumes: Task A1 的三个端点。
- Produces: `listAnnouncements(): Promise<{items: AnnouncementItem[], total}>`、`createAnnouncement(payload, key)`、`withdrawAnnouncement(id, key)`（AnnouncementItem = {id,title,content,scope,directionId?,directionName?,createdAt}）。

- [ ] **Step 1: 契约新增** `/api/v1/announcements`（GET + POST）与 `/api/v1/announcements/{announcementId}/withdraw`（POST，ErrorResponse 404/403）；`AnnouncementItem` schema（scope 枚举 COLLEGE/DIRECTION，directionId/directionName nullable）；`CreateAnnouncementRequest`（title 1-120、content 1-2000、directionId 可选）。两份 yaml `cp` 同步，md5 一致。
- [ ] **Step 2: `npm run gen:api --legacy-peer-deps`**
- [ ] **Step 3: api/modules/announcements.ts**（create 带 post(…, { idempotencyKey })，withdraw 同）
- [ ] **Step 4: DefaultLayout 横幅**：`onMounted` 调 listAnnouncements 取第一条未关闭的（关闭表 localStorage key `is.announcement-dismissed-<id>`）；el-alert 横幅 title=`[学院|方向名] title`，可关闭；关闭写 localStorage。**不阻塞挂载**（catch 静默）。
- [ ] **Step 5: AnnouncementsManageView**：表格（title/scope/方向/时间/状态 + 撤回按钮）；新建表单（title/content + 管理员方向下拉含「学院级」选项、负责人锁定本人所辖方向集合——若多个则下拉，一个则隐藏）；撤回 ElMessageBox 确认。
- [ ] **Step 6: 路由**（`/admin/announcements`，roles STAFF_ROLES，title 公告管理）+ 菜单（管理员与负责人「管理」组加「公告管理」）
- [ ] **Step 7: 前端测试**：管理页（管理员可发学院级+方向、负责人方向锁定、撤回确认）；横幅（展示 scope 标签、关闭写 localStorage、已关闭不再显示）
- [ ] **Step 8: `npx vitest run` + `npm run lint` 全绿**
- [ ] **Step 9: Commit** `feat(announcement): 公告横幅与管理页（契约/前端）`

### Task B: 成绩导出 Excel

**Files:**
- Create: `frontend/src/utils/scoreExport.ts`
- Modify: `frontend/src/views/oj/AssessmentResultsView.vue`（按学生汇总表头加「导出 Excel」按钮）
- Test: `frontend/src/utils/__tests__/scoreExport.spec.ts`

**Interfaces:**
- Consumes: `listAssessmentMemberScores(assessmentId, {page,size})`（oj.ts 已有）。
- Produces: `buildScoreRows(pages: AssessmentMemberScore[][]): Record<string,string|number>[]`（纯函数，可测）、`exportMemberScores(assessmentId: string, title: string): Promise<void>`（拉全量+写文件 `考核标题-成绩-YYYYMMDD.xlsx`）。

- [ ] **Step 1: 失败测试** buildScoreRows：输入两页 member-scores（第二页模拟 200 分页后续）→ 输出行数=全部成员；列含 排名（按 totalScore 降序，同分同名次）/姓名/学号/完成条目（`completedCount/itemCount`）/客观题状态（未交卷/已判分/N 题待评分）/总分/满分/得分率/最近提交（空→'未提交'）。
- [ ] **Step 2: 运行确认失败**（模块不存在）
- [ ] **Step 3: 实现** scoreExport.ts：buildScoreRows 纯函数；exportMemberScores 循环 `listAssessmentMemberScores(assessmentId,{page,size:200})` 直到 `items.length + (page-1)*200 >= total`，聚合调 buildScoreRows → `XLSX.utils.book_new/json_to_sheet` → `XLSX.writeFile`。
- [ ] **Step 4: 结果页按钮**：header 区 `导出 Excel`（canReview 可见，:loading，空数据 disabled，data-test="export-scores"），点击 `exportMemberScores(assessmentId, assessment.title)`，错误 ElMessage。
- [ ] **Step 5: 测试与 lint 全绿**（vitest run + lint）
- [ ] **Step 6: Commit** `feat(oj): 考核成绩按学生汇总导出 Excel`

### Task C: 登录态保持

**Files:**
- Modify: `frontend/src/api/tokenStore.ts`（sessionStorage 持久化 + restore）
- Modify: `frontend/src/stores/auth.ts`（restoreSession action）
- Modify: `frontend/src/main.ts`（bootstrap 挂载前调用恢复）
- Modify: `frontend/src/api/tokenStore.ts` 头注释、`docs/api/README.md` §3.2（契约修订：允许 sessionStorage 短效凭据，仍禁 localStorage）
- Test: `frontend/src/api/__tests__/tokenStore.spec.ts`（若无则建）、auth store 恢复用例并入现有 auth spec

**Interfaces:**
- Consumes: `client.ts` 的 `refreshAccessToken()`（single-flight 已有）、`authApi.getMe()`（已有）。
- Produces: `tokenStore.restore(): void`（从 sessionStorage 读回 refreshToken）、`auth.restoreSession(): Promise<boolean>`（true=已恢复会话）。

- [ ] **Step 1: 失败测试** tokenStore：`setTokens` 后 `sessionStorage.getItem('is.refresh')` 等于 refreshToken；`rotateTokens` 更新之；`clearTokens` 移除；新模块实例（simulate reload：先 sessionStorage.setItem 再 restore）能读回。auth.restoreSession：有 refreshToken 时调 refresh+me 并置 authenticated；refresh 拒绝时清 token 返回 false 且 sessionState='anonymous'。
- [ ] **Step 2: 运行确认失败**
- [ ] **Step 3: 实现**：tokenStore 增 `const STORAGE_KEY='is.refresh'`；setTokens/rotateTokens 写 sessionStorage（refreshToken 非 null 时）、clearTokens 移除、`restore()` 读回至内存 refreshToken；auth store 增 `restoreSession()`：`tokenStore.restore()` → 无 refreshToken 返回 false → `refreshAccessToken()`（client.ts 已导出？若未导出则导出）→ 成功后 `authApi.getMe()` applySession；任何失败 `clearTokens()` + false。main.ts bootstrap 在 `app.mount` 前调 `await auth.restoreSession()`（store 需在 createPinia 后取——用 `useAuthStore(pinia实例)`）。受限会话不受影响（无 refreshToken 不持久化）。
- [ ] **Step 4: 修订文档**：tokenStore 头注释、docs/api/README.md §3.2 改为「refreshToken 允许存 sessionStorage 以支持刷新恢复；localStorage 仍然禁止」。
- [ ] **Step 5: 测试与 lint 全绿；手测**：登录 → F5 刷新 → 不跳登录页且数据正常；退出 → 刷新 → 停留登录页。
- [ ] **Step 6: Commit** `feat(auth): 刷新页面静默恢复会话（sessionStorage refreshToken）`

### 收尾

- [ ] `./mvnw verify` 全绿（139→140+ IT）+ 前端 vitest/lint 全绿
- [ ] 本地重启后端（迁移 V14）+ API 冒烟：管理员发学院级+方向级公告 → 学生登录见学院级+本方向、他人方向不可见 → 导出 Excel 下载 → 刷新不掉线
- [ ] 分项 commit 已随任务完成；推送 `git push origin main`
