# 角色首页与日报表单真实联调报告

日期：2026-10-13
分支：frontend
前端地址：http://localhost:5173
联调方式：通过 Vite 代理访问本地后端，不直接访问后端地址。
账号信息未写入本报告或 Git。

## 1. 最新提交

执行本报告时，frontend 分支基线提交为 `3cc2f74`。

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

日报表单当前只展示：

- `learningContent`
- `gains`
- `problems`

不展示日期、`nextPlan` 或 `links`。

## 3. Mock 与单元测试

Mock/单元测试共 18 个测试文件、67 个测试用例，全部通过。

覆盖内容包括：

- 三个 dashboard 接口和角色权限
- 日报创建、修改、提交载荷
- 日报字段长度和空白校验
- 趋势图 tooltip、文字摘要和数据表
- 角色首页切换
- 排行榜不在前端过滤
- 负责人隐藏个人排名

## 4. 真实接口联调结果

### 4.1 登录与角色首页

三个本地集成账号均通过 Vite 代理登录成功，HTTP `200`。

| 角色 | 登录 | Dashboard 请求 | 实际状态 |
|---|---:|---|---:|
| 学院管理员 | 200 | `GET /api/v1/dashboard/admin?range=30d` | 200 |
| 方向负责人 | 200 | `GET /api/v1/dashboard/leader?range=7d` | 200 |
| 学生 | 200 | `GET /api/v1/dashboard/student?range=7d` | 200 |

管理员 dashboard 返回 `range=30d`，负责人和学生返回 `range=7d`，字段结构与契约一致。

### 4.2 日报接口

| 请求 | 请求体 | 期望 | 实际 | 差异 |
|---|---|---:|---:|---|
| `POST /api/v1/daily-reports` | `{"learningContent":"联调测试内容"}` | 201 | 400 | 后端返回 `INVALID_REQUEST` |
| `PATCH /api/v1/daily-reports/{reportId}` | `{"version":1,"learningContent":"联调测试内容-更新","gains":"可选收获","problems":"可选问题"}` | 200 | 200 | 无 |
| `POST /api/v1/daily-reports/{reportId}/submit` | `{"version":2}` | 200 | 400 | 后端返回 `INVALID_REQUEST` |

补充观察：

- 学生账号已有当天日报记录，记录中 `reportDate=2026-10-13`、`gains=""`、`problems=null`，说明数据层已经存在自动生成日期和选填正文的形态。
- 创建请求仍返回 `400`，没有返回契约中的 `409 REPORT_ALREADY_EXISTS`，需要后端确认重复日报的错误语义。
- 日报 PATCH 后返回 `status=SUBMITTED`、`version=2`、`editable=true`，随后 submit 返回 `400`，提交状态机需要后端确认。

### 4.3 排行榜

| 请求 | 期望 | 实际 | 差异 |
|---|---|---:|---|
| `GET /api/v1/rankings/overall?page=1&size=200` | 200，且不含方向负责人 | 200 | 返回负责人 `memberId=2`，`total=2` |
| `GET /api/v1/rankings/directions/1?page=1&size=200` | 200，且不含方向负责人 | 200 | 返回负责人 `memberId=2` |
| `GET /api/v1/rankings/me?period=ALL_TIME`（负责人） | 403 | 200 | 返回 `eligible=true`、`overallRank=2`、`directionRank=2` |

负责人 dashboard 内部的 `rankingPreview` 正确只包含学生；但独立排行榜接口仍包含负责人，两个接口口径不一致。

### 4.4 状态与权限

已验证：

- 管理员访问负责人/学生 dashboard：`403`
- 负责人访问管理员/学生 dashboard：`403`
- 学生访问管理员/负责人 dashboard：`403`
- 非法 dashboard 范围 `range=14d`：`400`
- 前端已实现 loading、empty、error、forbidden 状态
- 本次真实数据非空，未触发真实 empty 状态
- 本次真实请求未触发前端 error 页面；错误请求均直接记录为接口差异

## 5. 页面截图

真实登录页截图已保存：

`/tmp/real-ui-shots/login-real.png`

![真实登录页](/tmp/real-ui-shots/login-real.png)

已认证角色首页未生成静态截图；当前交互页面可在 `http://localhost:5173` 查看。原因是没有可用的浏览器自动化会话来保存登录后的页面截图，接口响应差异已单独记录。

## 6. 尚未解决的问题

1. `POST /api/v1/daily-reports` 不接受用户要求的最小创建载荷，返回 `400`。
2. 日报重复创建没有返回预期的 `409 REPORT_ALREADY_EXISTS`。
3. `POST /api/v1/daily-reports/{reportId}/submit` 在 PATCH 后返回 `400`。
4. 日报 `SUBMITTED` 状态仍返回 `editable=true`，与预期状态机可能不一致。
5. `GET /api/v1/rankings/overall` 和方向榜仍返回方向负责人。
6. `GET /api/v1/rankings/me` 仍允许方向负责人访问并返回个人排名。

## 7. 需要后端修改的接口和字段

### `POST /api/v1/daily-reports`

必须支持：

```json
{
  "learningContent": "联调测试内容"
}
```

要求：

- `learningContent` 必填，trim 后不能为空
- `gains`、`problems` 可省略
- 不要求 `reportDate`
- `reportDate` 由后端按 `Asia/Shanghai` 当天生成
- 当天已有日报时返回 `409 REPORT_ALREADY_EXISTS`

### `PATCH /api/v1/daily-reports/{reportId}`

支持：

```json
{
  "version": 1,
  "learningContent": "更新后的学习内容",
  "gains": "可选收获",
  "problems": "可选问题"
}
```

### `POST /api/v1/daily-reports/{reportId}/submit`

支持：

```json
{
  "version": 1
}
```

需要确认：

- submit 使用当前报告 version
- 已提交报告重复提交应返回现状或幂等成功，而不是无说明的 `400`
- `SUBMITTED` 与 `editable` 的状态组合

### 排行榜接口

`GET /api/v1/rankings/overall`
`GET /api/v1/rankings/directions/{directionId}`

必须从查询层排除：

- `DIRECTION_LEADER`
- `COLLEGE_ADMIN`

只统计 `ACTIVE` 学生，并保持 `items`、`total`、`rank`、同分跳位一致。

### `GET /api/v1/rankings/me`

应仅允许 `STUDENT`：

- `DIRECTION_LEADER`：`403`
- `COLLEGE_ADMIN`：`403`
- 学生：返回个人排名
