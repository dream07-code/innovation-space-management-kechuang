# API 契约 v1.0.0-draft

更新：2026-10-11 · 业务时区：`Asia/Shanghai` · 目标：前后端并行开发

**机器可读契约：[openapi.yaml](openapi.yaml)。这是设计先行的接口文档，不是已上线接口清单。**

校验结果、审查修正与复现命令见 [文档校验记录](VALIDATION.md)。

### backend 本轮实现说明

此副本在 backend 分支与 `main@37797e4` 的契约保持路径/DTO一致。当前backend新增认证、方向、成员基本管理、负责人管理、更正学号/密码重置与审计查询，以及日报/周报、评分、积分和排名接口；自动移出和管理员 `restore` 已实现；手动 `remove`、月评和月度任务不再实现。上文baseline标签为main发布时的历史基线，不应误读成backend当前状态。运行中的springdoc只覆盖已注册路径并标implemented，不能依据静态设计文件中的planned就断言真实接口不存在，亦不能把pending接口当已授权功能。详细验收以本阶段报告为准，本轮未向main推送状态更新。

## 1. 使用方式与实现状态

1. 前端将 `openapi.yaml` 作为 OpenAPI 3.0 文件导入 Apifox、Postman 或兼容工具，生成 Mock、类型和请求封装；不要把 Mock 地址当真实后端。
2. 后端按同一份路径、字段、状态码和权限实现。接口落地后再更新 `x-implementation-status`，并用集成测试核对生成的 springdoc 契约。
3. 联调时把文档 server `/` 替换为实际后端 origin。业务路径包含 `/api/v1`；不要重复添加前缀。
4. 本文件解释跨接口规则，YAML 负责路径、参数和 DTO。发现差异时先修正文档再联调，不让两份契约长期分叉。

| 标记 | 含义 |
| --- | --- |
| `implemented` | 已在指定后端基线实现，不表示 main 部署了服务 |
| `planned` | 本版设计契约，尚未实现；前端使用 Mock |
| `pending` | 契约草案且依赖业务决策；可以做页面骨架，不启用真实执行 |

后端基线为当前 `backend` 分支。认证、方向、成员、日报、周报、积分和排行榜接口已实现；月评/任务契约已删除；手动 `remove` 仍为未实现设计项。开发文档在 `/doc.html`，分组 JSON 在 `/v3/api-docs/innovation-space`，默认及 prod 不开放这些文档入口。这份 YAML 是设计契约，不会因放入 Git 自动出现在运行中的 Knife4j，也不得用测试探针冒充业务接口。

本次 main 只更新文档，没有合并 Java 工程，没有创建数据库账号，也不包含真实学生或负责人信息。

## 2. 已确认身份规则

| 角色 | 登录账号 | 创建/配置方式 | 管理范围 |
| --- | --- | --- | --- |
| `COLLEGE_ADMIN` 学院管理员 | 项目负责人指定的账号 | 项目负责人提供账号和密码，部署时安全初始化 | 全学院；可管理负责人信息 |
| `DIRECTION_LEADER` 方向负责人 | 学号 | 由学院管理员录入/任命，具体名单后续提供 | 获授权方向 |
| `STUDENT` 学生 | 学号 | 学院管理员或本方向负责人添加 | 本人数据、允许展示的排行榜 |

- 不提供 `/register`，不允许首次登录自动创建账号。
- 学生和负责人使用项目负责人已指定的统一初始口令；**实际口令不写进本仓库、示例或响应**，用部署私密配置 `MEMBER_INITIAL_PASSWORD` 提供。该变量是未来实现约定，当前工程没有读取它。
- 首次登录进入强制改密状态；学院管理员由本人指定的密码不是共享学生初始口令，本版默认不强制再次更改，初始化时可设置 `mustChangePassword=true`。
- 同一个人使用同一个内部 `memberId`/`userId`；任命负责人不创建第二个同学号账号。
- 学号在普通修改接口中不可改。更正必须走管理员专用接口，校验唯一、说明原因、保留原学号和操作者审计，并撤销旧会话；历史报告/积分继续关联不变的内部 ID，不物理迁移或重建数据。旧学号不再可登录。
- 学院管理员账号也在统一 `account` 命名空间内，不能与任何学号冲突。管理员账户不在学生创建接口中产生；该接口没有 `role`/`password`/`points` 入参。

## 2.2 日报表单字段（已确认）

日报创建/编辑表单只展示 `learningContent`、`gains`、`problems`：学习内容必填，收获和遇到的问题选填。日期由服务端按 `Asia/Shanghai` 当天生成，不展示日期选择器；`nextPlan`、`links` 仅作为历史兼容字段，不作为常用表单项。

## 2.1 排行榜资格（已确认）

- `GET /api/v1/rankings/overall` 和 `GET /api/v1/rankings/directions/{directionId}` 可由学院管理员、方向负责人和学生查看，但 `items`、`total`、rank、同分跳位只统计 `ACTIVE` 学生。
- `GET /api/v1/rankings/me` 仅面向 `STUDENT`。方向负责人前端隐藏或禁用“我的排名”，个人首页不展示排名卡片。
- 方向负责人仍可提交日报/周报、保留积分和积分流水，也可以查看公开排行榜；“不计入排行”不等于清零或禁止获得积分。
- 前端不得通过角色字段自行过滤排行榜行。后端必须从查询层排除方向负责人，否则 `total`、名次和同分计算都会错误。
- 移出学生不进入在籍排行榜；历史报告、积分流水和管理员恢复逻辑不变。

## 3. 认证与会话契约（本版技术设计）

### 3.1 登录

```http
POST /api/v1/auth/login
Content-Type: application/json

{"account":"MOCK20260001","password":"Example-Only9!"}
```

`MOCK20260001` 和 `Example-Only9!` 都是虚构示例，不是预置账号或可用凭据。

普通登录返回 HTTP 200，`code=OK`，`data` 包含 `accessToken`、`refreshToken`、`tokenType=Bearer`、`expiresIn`、`refreshExpiresIn`、`mustChangePassword=false` 和用户身份。

首次/重置后登录同样 HTTP 200、`code=OK`，但：

```json
{
  "code": "OK",
  "message": "Success",
  "data": {
    "accessToken": "mock-limited-token-not-a-credential",
    "refreshToken": null,
    "tokenType": "Bearer",
    "expiresIn": 300,
    "refreshExpiresIn": null,
    "mustChangePassword": true,
    "user": {
      "userId": "101", "memberId": "201", "account": "MOCK20260001",
      "displayName": "示例同学", "role": "STUDENT",
      "directionId": "1", "managedDirectionIds": []
    }
  }
}
```

**不得给待改密用户正常业务权限或 refresh token。** 受限 access token 只允许调用 `/auth/me`、`PUT /auth/password`、`/auth/logout`，其他受保护业务接口返回 HTTP 403、`PASSWORD_CHANGE_REQUIRED`。限制由后端执行，前端跳转不是安全控制。

### 3.2 Token 与退出

- 业务请求携带 `Authorization: Bearer <accessToken>`。前端将 token 当不透明字符串，不根据 JWT 内容自行判定权限。签名算法和存储技术不属于页面契约。
- 本版普通 access token 建议 900 秒、refresh token 604800 秒；受限 token 300 秒。**前端以响应 expiresIn 为准**，不能写死倒计时。均属可调整的技术默认值。
- Refresh token 通过 `/auth/refresh` 的 JSON 请求体交换并轮换，旧 refresh token 立即失效；服务端检测重复使用并撤销该 session。前端对刷新使用 single-flight，禁止多个并发刷新。刷新重新读取账号状态及权限，不从旧 token 复制过期权限。
- 本版不是 Cookie 认证：接口不设置认证 Cookie。前端 access token 只放内存；refreshToken 允许镜像到 sessionStorage（2026-10 修订）：同一标签页内刷新页面可静默恢复会话，关闭标签页即清除，仍然禁止持久 localStorage 与任何「记住登录」式长期存储。若后续改 HttpOnly Cookie，要同时修订 CORS/CSRF 和此契约。
- 登录、刷新响应带 `Cache-Control: no-store`。密码/token 不放 URL、日志、审计详情或错误回显；生产使用 HTTPS、登录限流、通用错误信息，不能通过登录响应枚举学号。
- `/auth/logout` 撤销当前 session 及其刷新能力；重复用已失效 token 退出可返回 401，前端仍清空本地状态。
- 修改密码、管理员重置密码、学号更正、停用/移出、负责人授权调整均立即撤销相关账号已有 session。不能只等 JWT 自然过期。
- 学生/负责人修改密码成功 HTTP 200、`data=null`，全部会话失效，**重新登录**；响应不再返回 token。新密码设计为 12–64 个字符，不接受全空白、当前密码或已配置共享初始口令；用户输入不静默 trim。旧密码错误返回 400 `OLD_PASSWORD_INVALID`，受限状态仍保留。
- 学生和负责人找回密码首期走学院管理员重置；不设计公开找回、验证码或邮件发信。重置接口不接收/返回新密码，使用私密初始口令并强制下次改密。负责人不能重置其他人的密码。
- 统一初始口令存在被他人抢先登录的风险，强制改密不能单独证明身份。上线前须核验账号交付方式、限制登录尝试并由管理人员确认领用；本版不声称该风险已经解决。

## 4. 公共协议

### 4.1 数据格式

```json
{"code":"OK","message":"Success","data":{"items":[],"page":1,"size":20,"total":0}}
```

- 内容类型 `application/json`；成功 `code=OK`。失败使用正确 HTTP 状态，`data=null`，不把失败包装成 HTTP 200。
- `/actuator/health` 为例外：原生 `{"status":"UP"}` 或 `{"status":"DOWN"}`，不套业务 envelope。
- 所有数据库 ID 为十进制**字符串**，避免 Java Long 转 JavaScript Number 精度损失；学号也是字符串，保留前导零，不强制转换数值。
- `page` 从 1 开始，默认 1；`size` 默认 20，范围 1–200。新业务控制器先拒绝超限参数（400），底层分页拦截器的 200 上限是第二道防线。
- 列表 data 固定 `items/page/size/total`；total 是过滤后的记录数，不是页数。稳定排序在各操作 description 中指定。
- 日期 `YYYY-MM-DD`，月份 `YYYY-MM`，时间戳 ISO 8601 带偏移，例如 `2026-10-11T09:00:00+08:00`。业务日期以服务端 `Asia/Shanghai` 判定，不用浏览器时区推断。
- PATCH 中省略字段=不变；仅声明 nullable 的可选字段允许 null 清空。空请求、未知字段、试图写只读字段均 400。名单/报告读接口不能被用来绕过字段可见性。
- 请求 DTO 不直接复用数据库实体。响应可扩展新增字段，客户端不依赖未声明字段；破坏性修改发布新契约版本。

### 4.2 乐观锁和幂等

- 更新/提交等资源变更请求带读取到的 `version`；版本不同返回 409 `VERSION_CONFLICT`，前端重新获取详情，不能盲重试覆盖。
- 所有 POST 业务写操作（不含登录/刷新/退出）要求 `Idempotency-Key`（客户端生成 UUID）。同一操作者、方法、路径、key、相同 JSON 语义在 24 小时内返回首次成功结果；同 key 不同请求体 409 `IDEMPOTENCY_CONFLICT`。
- 权限和账号状态检查先于缓存回放，账号被移出后不能凭旧 key 继续操作。version 检查在同 key 成功回放之后，网络重试不应因第一次已成功而误报版本冲突。
- 24 小时窗口之外仍由数据库周期唯一约束、任务状态和积分事件唯一键防重；不是仅靠短期缓存防止重复加分或移出。
- 报告提交 HTTP 200 表示有效提交和保底事务已完成，**不表示 AI 评分完成**；异步任务重试不新增第二笔保底分。

### 4.3 错误码

| HTTP | code | 前端处理 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST`、`OLD_PASSWORD_INVALID`、`PASSWORD_POLICY_VIOLATION` | 展示安全提示，修正输入 |
| 401 | `UNAUTHORIZED`、`TOKEN_EXPIRED`、`INVALID_CREDENTIALS`、`REFRESH_TOKEN_INVALID` | 单次刷新或重新登录；refresh 失败不循环 |
| 403 | `FORBIDDEN`、`PASSWORD_CHANGE_REQUIRED`、`ACCOUNT_DISABLED`、`MEMBER_REMOVED` | 不重试；切改密页或显示不可用 |
| 404 | `NOT_FOUND` | 资源不存在；越权 ID 查询也可统一用此码隐藏对象存在性 |
| 405 | `METHOD_NOT_ALLOWED` | 方法错误，保留 Allow 响应头 |
| 409 | `VERSION_CONFLICT`、`ACCOUNT_ALREADY_EXISTS`、`REPORT_ALREADY_EXISTS`、`REPORT_NOT_EDITABLE`、`INVALID_STATE`、`IDEMPOTENCY_CONFLICT`、`MONTH_CLOSED`、`SCORING_PENDING` | 重新读取状态；评分未结清须等待，不得按保底分淘汰或重复累计积分 |
| 415 | `INVALID_REQUEST` | 请求 Content-Type 错误 |
| 429 | `RATE_LIMITED` | 按 Retry-After（秒）退避 |
| 500 | `INTERNAL_ERROR` | 通用失败，不展示服务端堆栈 |
| 503 | `RULES_NOT_CONFIGURED`、`SERVICE_UNAVAILABLE` | 禁用相关执行按钮、提示稍后重试 |

基线仅实现公共异常码 `INVALID_REQUEST/NOT_FOUND/METHOD_NOT_ALLOWED/INTERNAL_ERROR`；其余为待实现业务码。`PASSWORD_CHANGE_REQUIRED` 是调用业务接口时的 403 错误码，不是成功登录的顶层 code。

## 5. 权限与对象边界

| 资源/动作 | 学院管理员 | 方向负责人 | 学生 |
| --- | --- | --- | --- |
| 查看方向目录 | 是 | 是 | 是 |
| 添加学生 | 全方向 | managedDirectionIds 内 | 否 |
| 管理成员列表/详情/资料 | 全方向 | 所辖方向普通学生 | 否，使用 auth/me 查看自己 |
| 任命、修改、取消负责人授权 | 是 | 否 | 否 |
| 学号更正、重置他人密码、恢复成员 | 是 | 否 | 否 |
| 移出学生 | 全方向 | 所辖方向普通学生 | 否 |
| 草稿、提交和修改本人报告 | 管理账号无成员身份，不提交 | 本版沿用其成员身份，可提交本人报告 | 本人 |
| 查看正式报告 | 全方向 | 提交时方向属于授权范围 | 本人 |
| 查看他人草稿 | 否 | 否 | 否 |
| 复核申请 | 管理账号不申请 | 本人报告 | 本人报告 |
| 人工评分（1–3 分） | 全方向 | 所辖方向学生，不能评本人报告 | 否 |
| 积分流水 | 全方向 | 当前所辖成员 | 本人 |
| 排行榜 | 允许的最小展示字段 | 同左 | 同左 |
| 月度规则/执行、任务重试、审计 | 是 | 否 | 否 |

角色继承、负责人是否参与积分及恢复政策仍属于本版建议，不等于学院已最终确认。OpenAPI 的 `x-roles` 是文档元数据，不代替后端逐对象校验。

- 负责人修改成员接口不能接收 role、managedDirectionIds；不能修改其他负责人或学院管理员。
- 普通学生没有成员通讯录权限。排行榜只返回 memberId、displayName、directionId/name、points、rank，不返回学号、电话、报告内容。
- 列表必须先做权限过滤再统计 total。日报/周报 list：成员默认本人，负责人默认本人加授权范围的正式报告，管理员仅正式报告。负责人提交 memberId 筛选时同样校验范围。
- 学生/负责人记录共享 Member DTO；通过 `/direction-leaders` 任命现有在籍学生，不新增重复学号。管理员先调用 `/members` 添加尚不存在的负责人档案，再任命。任命/撤销不重置密码、不删除历史，撤销后回到 STUDENT。
- 负责人自己的资料及其负责方向仅学院管理员通过负责人接口调整。移出负责人须先撤销授权，避免仍有管理权限的失效成员。

## 6. 接口目录

具体每个请求、字段约束、成功和失败响应以 YAML 为准。

| 模块 | 路径（省略 /api/v1） | 作用 |
| --- | --- | --- |
| 认证 | `POST /auth/login`、`POST /auth/refresh`、`POST /auth/logout` | 登录、刷新、退出 |
| 本人 | `GET /auth/me`、`PUT /auth/password` | 当前身份、改密 |
| 方向 | `GET /directions`、`GET /directions/{directionId}` | 七个预置方向 |
| 学生 | `GET/POST /members`、`GET/PATCH /members/{memberId}` | 列表、添加、详情、资料修改 |
| 账号纠错 | `POST /members/{memberId}/account-corrections` | 更正学号并留痕 |
| 密码重置 | `POST /members/{memberId}/password-reset` | 学院管理员重置学生/负责人密码 |
| 成员恢复 | `POST /members/{memberId}/restore` | 管理员恢复自动移出的学生；手动提前移出不在本期范围 |
| 负责人 | `GET/POST /direction-leaders`、`PATCH /direction-leaders/{memberId}`、`POST /direction-leaders/{memberId}/revoke` | 列表、任命、修改资料/授权、撤销任命 |
| 日报 | `GET/POST /daily-reports`、`GET/PATCH /daily-reports/{reportId}`、`POST /daily-reports/{reportId}/submit`、`POST /daily-reports/{reportId}/score` | 创建草稿、修改、正式提交、人工评分 |
| 周报 | 与日报一致，将 daily-reports 换为 weekly-reports | 自然周报告，含人工评分 |
| 复核 | `GET/POST /score-reviews`、`POST /score-reviews/{reviewId}/resolve` | 申请、处理评分争议 |
| 积分 | `GET /points/me`、`GET /points/ledger` | 本人概览、权限内流水 |
| 管理员加分 | `POST /members/{memberId}/points` | 管理员直接给学生增加正整数积分 |
| 排名 | `GET /rankings/overall`、`GET /rankings/directions/{directionId}`、`GET /rankings/me` | 总榜、方向榜、本人排名 |
| 规则 | `GET /system/rules` | 查看报告、自动移出和恢复规则 |
| 审计 | `GET /audits` | 只读脱敏操作记录 |

## 7. 日报、周报、积分联动

### 7.1 报告状态

`DRAFT → SUBMITTED`；scoreStatus 独立为 `NOT_REQUESTED/QUEUED/PROCESSING/SCORED/RETRYING/REVIEW_REQUIRED/MANUAL`。

1. POST 创建草稿，`memberId` 来自当前身份，客户端不能代他人提交。
2. PATCH 必须带 version；草稿允许空内容，submit 才校验学习内容及收获均非空白、日期窗口、账号在籍。
3. submit 对同一 reportId 已提交且当前 version 一致时返回现状，不再加分。相同成员、同一日期/周只有一条报告记录（含草稿），重复创建返回 409 `REPORT_ALREADY_EXISTS`，不采用“创建第二份再累加”。
4. 报告详情包含 `awardedPoints`、可空 `finalScore/scoreReason` 和评分状态。前端不能把 `finalScore=null` 渲染为 0 分，也不能自己加分。
5. 对已提交报告 PATCH 仅在 server 的 `editable=true` 时允许；必须先将 PATCH 合并现有正文，再对**完整结果**执行正式提交相同的必填、非空白校验。日报 learningContent/gains、周报 summary/achievements 均不能被清空或改为纯空白；失败 400 `INVALID_REQUEST`，不修改报告、积分或评分任务。草稿仍允许不完整。有效更新才增加 version 并重评，不重发保底分、不重置首次提交时间。待评分期间保留已入账分，完成后按差额调整；旧版本 AI 结果丢弃。manual 复核后的报告本版不可继续编辑，需学院管理员另行处理。
6. 前端通过 GET 详情轮询异步评分，建议退避至 3–10 秒，页面离开停止。AI 超时不让正式提交消失；REVIEW_REQUIRED 显示“待复核”。不向前端暴露模型密钥、原始提示词或原始模型输出。
7. 人工处理复核采用当前 reportVersion 和 review version 双重检查；人工结果优先于迟到 AI 结果。只新增差额流水，不能直接改总分。

### 7.2 未最终确认的业务默认（Mock 可采用，不自动上线）

| 项目 | 本版建议 | 生产启用前 |
| --- | --- | --- |
| 日报积分 | 有效提交先 1 分，最终合计 1–3 分，不是 2–4 分 | 确认分值解释 |
| 周报积分 | 提交先 1 分，最终合计 1–3 分 | 确认保底方案 |
| 日期窗口 | 日报当天；周报本周周一至周日；不补交 | 确认补交、修改和请假政策 |
| 月度归属 | 首次提交月份；跨月补分归原月；月度积分只查询 | 不执行月度评定或淘汰 |
| 同分排名 | 竞赛排名 1、1、3；同分展示按 memberId 数值升序 | 不触发任何自动移出 |
| 连续未交日报 | 14个完整自然日；加入/恢复日不计，草稿和周报无效 | 已确认并实现 |
| 恢复 | 开新在籍周期；报告和流水保留，净积分清零 | 已确认并实现 |
| 负责人 | 保留本人报告/积分，不执行日报不活跃自动移出 | 已确认 |
| 移出账号 | 禁止登录及业务访问，管理员可查历史 | 已确认 |

规则状态由 `/system/rules` 返回。前端不得按本机日期自行移出人员或按排名删除学生。

## 8. OJ 考核接口预留

OJ 考核系统当前只发布 `planned` 契约，前端可先行开发考核、题库、代码提交、OJ 判定、AI/人工评分和独立结果页面。OJ 产品、沙箱和真实编译运行由后端适配层接入，前端不得依赖 OJ 私有 API。

## 9. 自动移出与恢复安全边界

- 自动移出是内部调度，不提供普通用户触发任意成员移出的接口。
- 仅 `ACTIVE` 学生参与；按 `Asia/Shanghai` 的14个完整自然日判断，加入/恢复日不计，草稿和周报无效。
- 移出只更新成员资格、撤销会话、写审计并新增积分冲正；不删除报告或原始积分流水。
- 每条原始非0流水只有一个固定事件键的等额 `REVERSAL`，重复任务和恢复不会二次冲正。
- 自动移出后迟到评分任务标为 `STALE`，不得新增积分。
- `POST /members/{memberId}/restore` 仅学院管理员、版本受控且幂等；只恢复 `REMOVED` 学生。
- 恢复重置 `membershipStartedAt`，开始新的14个完整自然日观察期，净积分保持0，不恢复旧会话。
- 月度评定、月度淘汰和月度任务已取消，契约不再提供相关路径。
- 管理员加分接口不要求原因、version 或幂等键，每次调用均累计；本版不提供减分、管理员手动提前移出、物理删除人员、作废报告或公开创建管理员接口。

## 10. 前后端并行开发顺序与验收

1. **优先**：登录/强制改密/退出、方向目录、学生 CRUD、负责人资料/任命、学号更正。
2. **其次**：日报周报草稿/提交、异步评分状态、个人积分和排行榜。
3. **当前范围**：人工复核基础结构、自动移出、积分冲正和管理员恢复；月度评定/淘汰已取消。

联调至少覆盖：首次登录受限令牌不能访问业务、refresh 轮换、改密后旧 token 失效、负责人跨方向 403/404、学生不能创建学生、普通 PATCH 不能改学号/角色、重复提交不加分、过期 version 409、排行榜无联系方式、AI 失败仍保留提交、自动移出边界、幂等冲正和管理员恢复符合契约。

变更流程：契约先更新并说明兼容性，前后端同时同步同一 Git 提交；不能先改一端字段后要求另一端猜测。OpenAPI 文件经规范校验及示例/schema 校验后提交。该文档没有承诺所有技术默认值已实现，真实上线仍以安全测试和学院确认的业务规则为准。

## 4. 填空人工阅卷与成绩发布（2026-11 新增）

- 只有 `FILL_BLANK` 可人工阅卷；单选/多选/判断仍是自动判分终判。
- 作答为多行代码文本（上限 20000 字符），换行与缩进原样保存。
- 交卷时仍自动判分并写入 `autoAwarded`；人工分 `manualScore` 覆盖 `awarded`，撤销后回退自动分。
- 阅卷仅在考核 `CLOSED/ARCHIVED` 且未发布成绩时开放；发布后阅卷锁定。
- 发布成绩由 `POST /api/v1/assessments/{assessmentId}/publish-results` 触发，前置条件：已关闭、无待批填空、`version` 匹配。
- 发布后学生才可见总分、逐题得分与评语；未发布时 `my-answer-sheet`、`member-scores` 均不返回分数。
- 考核积分从“关闭时结算”改为“发布成绩时结算”，事件键不变，幂等。
- `quiz-review/summary` 同时下发本场全部填空题（`questions`，含完整题干与参考答案），阅卷页一次请求即可渲染题目列表，不再逐题回查题库。
- `quiz-review` 列表页大小上限 200；单场几十人的规模可直接一次取全，超过 200 条时前端才显示分页。
- 进度口径区分两个数字：`progress` 是**本题**已批人数/已交卷人数，`pendingItems` 是**全场**跨所有填空题的待批明细数。
