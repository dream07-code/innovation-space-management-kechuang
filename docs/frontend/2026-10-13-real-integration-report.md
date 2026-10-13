# 角色首页与日报表单真实联调报告

日期：2026-10-13
分支：frontend
前端地址：http://localhost:5173
联调方式：通过 Vite 代理访问本地后端，不直接访问后端地址。
测试账号仅用于本机集成测试，未写入本报告或 Git。

## 1. 最新提交

执行本次复测前，frontend 分支最新提交为 `1e979f1 docs: sync daily and ranking fixes`。

## 2. 已完成页面和组件

- 管理员数据看板
- 方向负责人数据看板
- 学生个人数据看板
- DashboardHeader
- DashboardOverviewStrip
- DashboardTrendChart
- AttentionList
- RankingPreview
- DashboardSection
- DashboardEmptyState
- DashboardErrorState
- DashboardSkeleton
- 日报创建/编辑表单

日报表单只展示 `learningContent`、`gains`、`problems`，不展示日期、`nextPlan`、`links`。

## 3. Mock 与单元测试

- 测试文件：18
- 测试用例：67
- 结果：全部通过
- `type-check`：通过
- `lint`：通过
- `build`：通过

## 4. 真实接口联调结果

### 4.1 登录与角色首页

三个角色均通过 Vite 代理登录成功。

| 角色 | 登录 | Dashboard 请求 | 实际状态 |
|---|---:|---|---:|
| 学院管理员 | 200 | `GET /api/v1/dashboard/admin?range=30d` | 200 |
| 方向负责人 | 200 | `GET /api/v1/dashboard/leader?range=7d` | 200 |
| 学生 | 200 | `GET /api/v1/dashboard/student?range=7d` | 200 |

响应范围：

- 管理员：`range=30d`
- 负责人：`range=7d`
- 学生：`range=7d`

Dashboard DTO 字段与 OpenAPI 契约一致。

### 4.2 日报接口

| 请求 | 请求体 | 期望 | 实际 | 差异 |
|---|---|---:|---:|---|
| `POST /api/v1/daily-reports` | `{"learningContent":"联调测试内容"}` | 201 | 400 | 返回 `INVALID_REQUEST` |
| `PATCH /api/v1/daily-reports/{reportId}` | `{"version":3,"learningContent":"联调更新","gains":"可选","problems":"可选"}` | 200 | 200 | 无 |
| `POST /api/v1/daily-reports/{reportId}/submit` | `{"version":4}` | 200 | 400 | 返回 `INVALID_REQUEST` |

补充观察：

- 学生账号已有当天日报，因此无法在同一账号上再次验证创建成功。
- 已有日报的 `reportDate` 自动生成，`gains` 和 `problems` 可以有值，说明响应 DTO 与表单字段兼容。
- 创建重复日报当前返回 `400`，没有按契约返回 `409 REPORT_ALREADY_EXISTS`。
- PATCH 成功后报告仍为 `status=SUBMITTED`、`editable=true`、`scoreStatus=QUEUED`，随后 submit 返回 `400`。

### 4.3 排行榜复测

最新后端修复后结果如下：

| 请求 | 期望 | 实际 | 结果 |
|---|---|---:|---|
| `GET /api/v1/rankings/overall?page=1&size=200` | 200，只包含 ACTIVE 学生 | 200 | 通过 |
| `GET /api/v1/rankings/directions/1?page=1&size=200` | 200，只包含 ACTIVE 学生 | 200 | 通过 |
| `GET /api/v1/rankings/me?period=ALL_TIME`（负责人） | 403 | 403 | 通过 |
| `GET /api/v1/rankings/me?period=ALL_TIME`（管理员） | 403 | 403 | 通过 |

总榜和方向榜均未包含方向负责人；`items`、`total`、`rank` 均直接使用后端返回值。负责人 dashboard 的 `rankingPreview` 也只包含学生。

### 4.4 权限与状态

已验证：

- 跨角色 dashboard 访问：403
- 非法范围 `range=14d`：400
- 负责人隐藏前端个人排名
- 负责人仍可查看总榜、方向榜和积分流水
- 学生页面只请求本人 dashboard
- 前端 loading、empty、error、forbidden 状态已实现
- 本次真实数据非空，未触发 empty
- 本次未触发前端 error 页面，错误请求均记录为接口差异

## 5. 页面截图

真实登录页截图：

`/tmp/real-ui-shots/login-real.png`

![真实登录页](/tmp/real-ui-shots/login-real.png)

已认证首页截图未生成静态文件；当前真实页面可在 `http://localhost:5173` 查看。

## 6. 当前未解决问题

1. `POST /api/v1/daily-reports` 最小请求体仍返回 `400`。
2. 当天重复日报应返回 `409 REPORT_ALREADY_EXISTS`，当前返回 `400`。
3. `POST /api/v1/daily-reports/{reportId}/submit` 在 PATCH 后返回 `400`。
4. `SUBMITTED` 报告仍返回 `editable=true`，状态机需要后端确认。

## 7. 需要后端确认或修改

### `POST /api/v1/daily-reports`

支持：

```json
{
  "learningContent": "联调测试内容"
}
```

要求：

- `learningContent` 必填且 trim 后不能为空
- `gains`、`problems` 可省略
- `reportDate` 由后端按 `Asia/Shanghai` 当天生成
- 当天重复创建返回 `409 REPORT_ALREADY_EXISTS`

### `POST /api/v1/daily-reports/{reportId}/submit`

支持：

```json
{
  "version": 1
}
```

需要确认：

- 使用当前报告 version
- 已提交报告重复提交应返回当前状态或幂等成功
- `SUBMITTED` 与 `editable=true` 的状态组合

### 排行榜

最新后端复测已经符合规则：

- 只统计 ACTIVE 学生
- 方向负责人和管理员不进入榜单
- 负责人和管理员访问 `/rankings/me` 返回 403
- 前端直接使用 `items/total/rank`
