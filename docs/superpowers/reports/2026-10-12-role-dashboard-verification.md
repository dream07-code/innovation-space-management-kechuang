# 角色化数据看板交付验证报告

日期：2026-10-13  
分支：`backend`  
基线：`66256d9`  
交付头：本轮验证提交前 `HEAD`  
计划：`docs/superpowers/plans/2026-10-12-role-dashboard.md`

## 1. 交付内容

实现三个独立的只读数据看板：

- `GET /api/v1/dashboard/admin?range=7d|30d`：学院管理员全院聚合、趋势、方向对比、待办、脱敏活动和 OJ 数据。
- `GET /api/v1/dashboard/leader?range=7d|30d`：方向负责人授权方向聚合、学生安全摘要、趋势、待办和学生排行榜预览。
- `GET /api/v1/dashboard/student?range=7d|30d`：学生本人积分、排名、报告、OJ、积分流水和进度。

前端交接文档位于 `docs/frontend/2026-10-12-role-dashboard-handoff.md`，覆盖三个页面布局、响应式、loading/empty/error 状态、图表可访问性和验收项。

## 2. 规则确认

- 管理员包含 OJ 考核数据。
- 管理员默认范围 `30d`，负责人和学生默认 `7d`。
- 不活跃风险列表最多10条。
- 排行榜、排名人数、同分跳位仅统计 `ACTIVE` 学生；方向负责人不进入任何排行榜。
- 负责人保留积分和积分流水，仍可查看总榜和方向榜。
- 前端不自行过滤排行榜数据；后端 SQL 层负责范围和角色过滤。

## 3. 自动验证

| 检查 | 命令/方法 | 结果 |
| --- | --- | --- |
| 全量构建 | `backend/mvnw -f backend/pom.xml -B -ntp clean verify` | 50 个单元测试、105 个 MySQL 集成/打包测试，0 failure/error |
| Dashboard 契约 | `DashboardApiContractIT` | 通过，三条路径均为 `implemented` |
| 隐私/只读 | `DashboardPrivacyIT` | 通过，响应无敏感字段，积分流水无写入 |
| 管理员看板 | `AdminDashboardHttpIT` | 4 项通过，包含 OJ、趋势、方向聚合、只读和边界 |
| 负责人看板 | `LeaderDashboardHttpIT` | 3 项通过，授权方向隔离、角色矩阵和同分排名 |
| 学生看板 | `StudentDashboardHttpIT` | 3 项通过，本人数据、角色矩阵、范围和同分排名 |
| OpenAPI | `openapi-spec-validator` | `OK` |
| 示例校验 | OAS Read/Write Validator | 73 个示例通过 |
| 契约副本 | `cmp` + SHA-256 | `docs/api/openapi.yaml` 与 `backend/src/main/resources/api-contract.yaml` 字节一致 |
| 依赖树 | `mvn dependency:tree -Dverbose` | 316 行输出，构建成功 |
| 密钥扫描 | 扫描 tracked 文件的私钥和常见 Token 模式 | 238 个文件未发现真实硬编码密钥 |
| Knife4j | Playwright 真实浏览器点击测试探针 | `page=PASS`, `browserPing=PASS`, HTTP 200, `data=pong`, 0 JS/网络错误 |
| diff 检查 | `git diff --check` | 通过 |

## 4. 独立审查与修复

本轮独立审查发现并用 RED→GREEN 测试修复两个 Important 问题：

1. **方向积分聚合被 JOIN 重复计算**：管理员 `directions.averagePoints` 在报告、评分任务、复核和 OJ 记录同时存在时重复累加 `point_ledger`。已改为相关子查询，新增 `AdminDashboardHttpIT` 断言验证 1.5 平均分。
2. **同分排名不是竞赛排名**：三方同分时个人排名可能被计算为 1、2、3。已修复学生和负责人排名计算，并新增三方同分测试，验证所有并列成员均为 rank 1。

审查未发现需要继续修复的 Critical/Important 问题。

## 5. 安全与数据边界

- SQL 先按 `member/app_user/direction` 过滤，再计算列表和 total。
- 学生接口强制使用当前 `memberId`，不接受请求参数选人。
- 负责人接口强制使用 `managedDirectionIds`。
- 看板只读，不写 `point_ledger`、报告、成员、评分或 OJ 状态。
- 响应不含学号、联系方式、报告正文、代码、私有测试数据、模型原始输出、API Key、密码或 Token。
- `range` 只接受 `7d/30d`，非法值返回 `400 INVALID_REQUEST`。
- 排除方向负责人后，排名人数、同分跳位和分页语义保持一致。

## 6. 前端交付状态

前端应按 `docs/frontend/2026-10-12-role-dashboard-handoff.md` 分别实现管理员、负责人和学生首页：

- 管理员：运营总览、趋势、方向对比、风险/评分/OJ 待办。
- 负责人：方向概览、学生状态表、趋势、待办、方向排行榜。
- 学生：积分排名、学习进度、报告、OJ、积分流水。

页面应采用数据表、趋势图、状态列表和排行榜预览，不使用整页相同尺寸卡片堆叠。前端只消费 `x-implementation-status: implemented` 的 dashboard DTO，并根据角色切换页面。

## 7. 结论

角色化数据看板后端 API、OpenAPI 契约、隐私边界、角色权限、排名规则和前端交接文档已完成，当前 `backend` 分支可以继续进行前端页面开发和联调。真实部署前仍需进行前端三角色 UAT、移动端验收和生产环境权限/备份检查。
