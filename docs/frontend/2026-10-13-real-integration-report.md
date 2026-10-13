# 角色首页与日报表单真实联调报告

日期：2026-10-13
分支：frontend
前端地址：http://localhost:5173
联调方式：通过 Vite 代理访问最新后端，不直接访问后端地址。
测试账号仅用于本机集成测试，未写入本报告或 Git。

## 1. 最新提交

本次复测时 frontend 分支最新提交为 `3c90cf7 chore(frontend): regenerate contract types`。

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

日报表单只展示 `learningContent`、`gains`、`problems`，不展示日期、`nextPlan` 或 `links`。

## 3. Mock 与单元测试

- 测试文件：18
- 测试用例：67
- 结果：全部通过
- `type-check`：通过
- `lint`：通过
- `build`：通过

## 4. 真实接口复测

### 4.1 角色登录与首页

| 角色 | 登录 | Dashboard | 实际结果 |
|---|---:|---|---:|
| 学院管理员 | 200 | `GET /api/v1/dashboard/admin?range=30d` | 200 |
| 方向负责人 | 200 | `GET /api/v1/dashboard/leader?range=7d` | 200 |
| 学生 | 200 | `GET /api/v1/dashboard/student?range=7d` | 200 |

Dashboard 范围和 DTO 均符合契约。

### 4.2 日报最小载荷

使用新学生账号和有效 UUID `Idempotency-Key`，通过 Vite 代理验证：

#### 首次创建

```http
POST /api/v1/daily-reports
Content-Type: application/json
Idempotency-Key: <UUID>
```

```json
{
  "learningContent": "Vite 最小载荷"
}
```

结果：

```text
201
```

响应包含：

- `reportDate` 由后端按当天生成
- `gains=""`
- `problems=null`
- 不需要前端发送 `reportDate`

#### 当天重复创建

请求体保持不变，使用新的 UUID `Idempotency-Key`：

```text
409 REPORT_ALREADY_EXISTS
```

#### PATCH 后提交

```http
PATCH /api/v1/daily-reports/{reportId}
```

```json
{
  "version": 2,
  "learningContent": "Vite 最小载荷-更新",
  "gains": "可选收获",
  "problems": "可选问题"
}
```

结果：

```text
200
```

使用 PATCH 响应中的 `version` 和新的 UUID `Idempotency-Key`：

```http
POST /api/v1/daily-reports/{reportId}/submit
```

```json
{
  "version": 3
}
```

结果：

```text
200
```

#### `SUBMITTED + editable=true`

当前业务规则允许当天修改已提交日报：

- `status=SUBMITTED`
- `editable=true`
- `editableUntil` 为当天结束时间
- PATCH 生成新版本
- 使用 PATCH 返回的最新 version 继续 submit 成功

该状态组合为有意支持，不是前端问题。

### 4.3 排行榜

| 请求 | 实际结果 | 结论 |
|---|---:|---|
| `GET /api/v1/rankings/overall` | 200 | 只包含 ACTIVE 学生 |
| `GET /api/v1/rankings/directions/1` | 200 | 只包含学生 |
| 负责人 `GET /api/v1/rankings/me` | 403 | 不提供个人排名 |
| 管理员 `GET /api/v1/rankings/me` | 403 | 不提供个人排名 |

前端直接渲染后端 `items/total/rank`，不删除负责人行，也不重算排名。

### 4.4 权限与状态

已验证：

- 跨角色 dashboard 访问返回 403
- 非法范围 `range=14d` 返回 400
- 负责人隐藏“我的排名”和个人首页排名卡片
- 学生只显示本人数据
- Loading、Empty、Error、Forbidden 状态已实现
- 图表提供 tooltip、文字摘要和可访问数据表

## 5. 页面截图

真实登录页截图：

`/tmp/real-ui-shots/login-real.png`

![真实登录页](/tmp/real-ui-shots/login-real.png)

已认证页面可在 http://localhost:5173 直接查看。

## 6. 复测结论

先前日报 `400` 是测试请求使用了非 UUID 格式的 `Idempotency-Key` 导致的测试工具问题，不是最小载荷问题。

改用有效 UUID 后，真实接口结果为：

```text
POST create → 201
POST duplicate → 409 REPORT_ALREADY_EXISTS
PATCH → 200
POST submit with PATCH version → 200
```

当前未发现需要前端修改的日报或排行榜契约差异。

## 7. 需要后端继续保证的接口语义

- `POST /api/v1/daily-reports` 接受最小载荷 `{"learningContent":"..."}`
- `reportDate` 由后端按 `Asia/Shanghai` 当天生成
- 当天重复创建返回 `409 REPORT_ALREADY_EXISTS`
- `PATCH` 使用当前 version
- `submit` 使用 PATCH 响应中的最新 version
- POST 写操作的 `Idempotency-Key` 使用 UUID
- 排行榜只统计 ACTIVE 学生
- 负责人和管理员访问 `/rankings/me` 返回 403
