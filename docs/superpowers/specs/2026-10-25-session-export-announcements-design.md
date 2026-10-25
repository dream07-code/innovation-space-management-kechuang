# 迭代设计：登录态保持 · 成绩导出 Excel · 站内公告

日期：2026-10-25。范围按用户推荐确认（数据库自动备份按用户指示移出范围）。

## 1. 登录态保持（刷新页面不掉线）

### 现状
- 401 single-flight 刷新、请求重放、token 轮换**全部已实现**（client.ts），后端 refresh 接口带轮换与重用检测。
- 唯一缺口：`tokenStore.ts` 只用模块级内存变量，刷新页面 token 全丢 → 必须重新登录。契约 `docs/api/README.md §3.2` 明文规定「不为记住登录使用持久 localStorage」。

### 方案对比
- **A. sessionStorage 持久化 refreshToken + 启动静默恢复（选定）**：tab 会话内刷新不掉线；关闭标签页即清除，长期驻留风险远小于 localStorage。改动小、复用现有刷新与轮换链路。
- B. localStorage 长期记住：体验最好但与契约安全立场冲突（XSS 长期驻留），否决。
- C. 不做：日常痛点保留。

### 设计（方案 A）
1. `tokenStore`：refreshToken 同步写入 sessionStorage（key `is.refresh`）；`setTokens/rotateTokens/clearTokens` 同步维护；启动时 `restore()` 读回。
2. 应用启动（main.ts bootstrap，路由守卫之前）：若有 refreshToken → `POST /auth/refresh` 静默换新 → 成功后 `GET /auth/me` 恢复用户 → 进入正常会话；失败（过期/重用）→ 清 storage，走匿名态。
3. 契约文档 §3.2 修订：允许 sessionStorage 短效凭据 + 明确「仍禁止 localStorage」；注释同步 tokenStore 头部。
4. 边界：受限改密会话（无 refresh token）不持久化，行为不变；登出/改密/刷新失败清 storage。

### 不做
- 记住密码、多 tab 同步（sessionStorage 天然按 tab 隔离，各 tab 独立会话可接受）、刷新倒计时 UI。

## 2. 考核成绩导出 Excel

### 方案对比
- **A. 前端 SheetJS 生成 xlsx（选定）**：`xlsx@0.18.5` 已在依赖（客观题导入在用），零后端改动，样式列宽可控。
- B. 后端 CSV 流：要动契约+后端+编码问题（Excel 打开 CSV 中文乱码需 BOM），无优势，否决。

### 设计
1. 结果页「按学生汇总」头部加「导出 Excel」按钮（工作人员可见）。
2. 数据：循环 `GET /member-scores?size=200` 拉全量（后端上限 200/页）。
3. 列：排名（按总分序，同分并列）、姓名、学号、完成条目、客观题状态、总分、满分、得分率、最近提交。文件名 `考核标题-成绩-YYYYMMDD.xlsx`。
4. 导出中 loading；分页数据为空时按钮禁用。

### 不做
- 按提交明细导出、人工评分明细列（后续按需）。

## 3. 站内公告（通知 MVP）

### 方案对比
- **A. 两级公告：学院级 + 方向级（选定，用户确认）**：管理员发学院级（所有人可见）和任意方向级；方向负责人发本方向公告（仅本方向可见）。一个表 + 三个接口 + 一个管理页，覆盖「学院通知全体」和「方向通知成员」两类场景。
- B. 逐用户站内信（收件箱/已读）：体验更细但表结构和页面翻倍，列后续迭代。

### 设计
1. **V14 迁移**：`announcement(id, title VARCHAR(120), content VARCHAR(2000), direction_id BIGINT NULL FK direction, created_by BIGINT FK app_user, created_at, withdrawn_at DATETIME NULL)`。`direction_id IS NULL` = 学院级；非空 = 方向级。撤回=置 withdrawn_at，软删除。
2. **可见性**（GET 按当前身份过滤）：
   - 学院级公告：所有登录用户可见；
   - 方向级公告：该方向学生、该方向负责人（所辖方向含它）、学院管理员可见；
   - 实现：学生/负责人按 `direction_id IN (可见方向集合)`，管理员不加过滤；学生可见集合 = 自己方向，负责人 = managedDirectionIds。
3. **后端**：
   - `GET /api/v1/announcements`：当前用户可见且未撤回，created_at 倒序，默认前 5 条；响应带 scope（COLLEGE/DIRECTION）与 directionName；
   - `POST /api/v1/announcements`（幂等键）：body `{title, content, directionId?}`。管理员：不带 directionId 发学院级，带则可发任意方向；负责人：必须带 directionId 且必须在其 managedDirectionIds 内（否则 403/400）；学生 403；directionId 不存在 404；
   - `POST /api/v1/announcements/{id}/withdraw`（幂等键）：管理员可撤回任何公告；负责人仅可撤回本方向公告（他人方向 404）；重复撤回幂等成功。
   - 审计 `ANNOUNCEMENT_CREATED/WITHDRAWN`（detail 记 scope 与 directionId）。
4. **前端**：
   - DefaultLayout 顶部横幅展示最新一条未关闭公告（列表接口已是按身份过滤后的结果；可关闭，记录 localStorage `is.announcement-dismissed-<id>`；学院级标「学院」，方向级标方向名）；
   - 管理员与负责人菜单新增「公告管理」（`/admin/announcements`）：列表（自己的可见发布记录）+ 新建（title/content + 管理员可选「学院级/某方向」，负责人固定本方向下拉所辖）+ 撤回（确认弹窗）；
   - 路由/菜单/契约/types.gen.ts 同步。
5. **测试**：后端 AnnouncementHttpIT（管理员发学院级+方向级、负责人发本方向/越权方向 403、学生只见学院级+本方向、撤回幂等与越权、限长校验）、迁移数 13→14；前端公告管理页 spec（负责人方向锁定）+ 横幅展示/关闭逻辑 spec。

### 不做
- 定时上下架、富文本、按方向定向、逐用户已读。

## 交付顺序与提交
按 3 → 2 → 1 推进（公告最大先做，导出其次，登录态最后涉及启动链路最谨慎），每项独立提交并推送；每项完成后本地 API 冒烟。
