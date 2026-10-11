# 学生日报连续未提交自动移出与管理员恢复设计

日期：2026-10-11  
版本：v1.0-draft  
状态：等待规格确认

## 1. 目标

取消月度评定和月度末位淘汰。对学生执行明确的日报活跃规则：在当前在籍周期内连续14个完整自然日没有正式提交日报时自动移出；学院管理员可以手动恢复被自动移出的学生。

成功标准：

- 只有 `STUDENT` 会因日报不活跃被自动移出。
- 草稿、周报、评分回调和积分变化都不算日报活跃。
- 14个完整自然日按 `Asia/Shanghai` 自然日计算，边界可重复测试。
- 自动移出幂等，撤销会话并保留报告和完整积分流水；成员累计分及报告净得分清零。
- 学院管理员可通过幂等恢复接口恢复学生；恢复开始新的在籍周期和新的14日观察期，积分从0开始。
- 月度评定、月度淘汰规则、快照、确认和执行功能从路线图及 API 契约中删除。

## 2. 范围与非目标

### 2.1 本期实现

1. 日报活跃日期计算和14个完整自然日判断。
2. 定时扫描 `ACTIVE` 学生并自动移出。
3. 自动移出审计、会话撤销和认证版本递增。
4. 学院管理员恢复 `REMOVED` 学生。
5. OpenAPI/Knife4j、运行配置、迁移和完整测试。
6. 删除月度评定/淘汰 API、规则和文档承诺。
7. 更新需求文档，明确不再实施月度末位淘汰。

### 2.2 非目标

- 学院管理员手动提前移出学生；本期移出只由14日规则触发。
- 月度评定、月度淘汰人数、保护名单和同分淘汰。
- 删除、迁移或篡改历史积分流水/报告。清零通过可审计的冲正流水完成。
- 恢复时自动恢复旧会话或旧认证状态。
- 请假、节假日、补交和特殊豁免。规则未配置这些例外。
- 一位成员多个方向。
- 多实例分布式调度；当前仍采用单进程定时任务和 MySQL 事务边界。
- `DIRECTION_LEADER` 的不活跃自动移出。负责人是否参评属于另一项业务规则。

## 3. 角色与权限

| 角色 | 自动移出 | 恢复 | 数据保留 |
| --- | --- | --- | --- |
| `STUDENT` | 满足规则时自动执行 | 不能调用恢复接口 | 报告和流水保留，净积分清零 |
| `DIRECTION_LEADER` | 不执行 | 不能恢复 | 不变 |
| `COLLEGE_ADMIN` | 不执行 | 可恢复符合条件的学生 | 可查询成员和审计 |

学生仍不能自注册。自动任务不是用户接口，不接收前端参数。

## 4. 时间口径

- 业务时区固定为 `Asia/Shanghai`。
- “完整自然日”指已经结束的上海自然日；当前正在进行的自然日不计入完整日数量。
- 日报活跃日期取当前在籍周期内最新一份 `status=SUBMITTED`、`report_type=DAILY` 报告的 `first_submitted_at` 所在上海自然日。
- `DRAFT` 日报不计数。
- `WEEKLY` 报告不计数。
- 成员刚加入或刚恢复时，`membershipStartedAt` 所在自然日是观察期起点日，不计入14个完整日。
- 恢复前的日报不得延长恢复后的观察期；活跃查询必须限制 `first_submitted_at >= membershipStartedAt`。
- 基准日为当前周期最后日报活跃日；无日报时为 `membershipStartedAt` 所在日。
- 基准日之后已经结束且没有日报的连续自然日达到14天时移出。等价边界：当前上海日期达到 `基准日 + 15天` 时即可执行。

示例：

| 情况 | 结果 |
| --- | --- |
| 10月1日加入，10月15日23:59检查 | 10月2日至10月15日尚有日期未结束，不移出 |
| 10月1日加入，10月16日00:00后检查 | 10月2日至10月15日共14个完整自然日，移出 |
| 10月10日提交日报，10月24日23:59检查 | 10月11日至10月24日未全部结束，不移出 |
| 10月10日提交日报，10月25日00:00后检查 | 10月11日至10月24日共14个完整自然日，移出 |
| 10月10日只有草稿 | 草稿不重置计时 |

## 5. 状态流转

```text
ACTIVE --连续14个完整自然日无正式日报--> REMOVED --学院管理员恢复--> ACTIVE
```

### 5.1 自动移出

条件同时满足：

1. `member.membership_status='ACTIVE'`。
2. 关联 `app_user.role='STUDENT'`。
3. 当前在籍周期内不存在正式日报，或最新正式日报按第4节计算已超过14个完整自然日。

执行事务：

1. 以条件更新锁定成员并再次校验 `ACTIVE`。
2. 写入 `membership_status='REMOVED'`。
3. 写入 `removed_at=业务当前时间`。
4. 写入 `removal_reason='TWO_WEEKS_NO_DAILY_REPORT'`。
5. `member.version=version+1`，更新 `updated_at`。
6. `app_user.auth_revision=auth_revision+1`。
7. 撤销该用户全部现有会话，原因 `MEMBER_AUTO_REMOVED`。
8. 为该成员所有非0原始积分流水生成等额 `REVERSAL` 冲正，使总分、每份报告净得分和各历史月份净分均为0。
9. 写审计 `MEMBER_AUTO_REMOVED`，`actor_user_id=null`。
10. 提交事务。

积分清零规则：

- 不删除或修改 `point_ledger` 原始行。
- 对每条 `delta<>0` 且 `source<>'REVERSAL'` 的原始流水，新增一条 `source='REVERSAL'`、`delta=-原delta` 的流水。
- 冲正行复用原始行的 `report_id`、`report_version_id` 和 `business_effective_at`，因此报告 `awardedPoints`、成员总分和历史月份净分都会归0。
- `recorded_at` 使用实际移出时间，保留“何时清零”的审计事实。
- `event_key` 使用 `MEMBERSHIP_POINTS_RESET:<memberId>:<originalLedgerId>:INACTIVE_DAILY_REPORT_14_COMPLETE_DAYS_V1`，由唯一键保证幂等。
- 若累计分已经为0，不创建额外空流水。
- 自动移出后迟到的 AI 结果不得再增加积分；评分 Worker 应跳过已移出成员并把任务标为 `STALE`。

幂等性：

- 只有条件更新实际修改一行时才写审计。
- 同一批次或重复调度不得产生第二条自动移出审计。
- 已是 `REMOVED` 的成员不重复处理。
- 多实例部署不在本期承诺范围；SQL 仍使用条件更新减少重复风险。

### 5.2 管理员恢复

接口：

```http
POST /api/v1/members/{memberId}/restore
Authorization: Bearer <college-admin-token>
Idempotency-Key: <uuid>
Content-Type: application/json

{
  "version": 2,
  "reason": "已联系学生确认返组"
}
```

约束：

- 仅 `COLLEGE_ADMIN`。
- 目标必须是 `REMOVED` 的 `STUDENT`；其他角色或 `ACTIVE` 学生返回 `409 INVALID_STATE`。
- `version` 必须匹配当前成员版本，否则 `409 VERSION_CONFLICT`。
- `reason` 必填，长度5至1000。
- `Idempotency-Key` 必填；相同请求重放同一结果，不同请求复用 Key 返回 `409 IDEMPOTENCY_CONFLICT`。
- 不接收或返回密码、积分、报告内容。

恢复事务：

1. 锁定成员和用户，校验角色、状态和版本。
2. `membership_status='ACTIVE'`。
3. `membership_started_at=业务当前时间`，开始新的观察期。
4. `removed_at=null`，`removal_reason=null`。
5. `member.version=version+1`。
6. `app_user.auth_revision=auth_revision+1`。
7. 撤销旧会话，原因 `MEMBER_RESTORED`。
8. 再次执行幂等积分清零，吸收移出后可能出现的迟到流水，确保恢复时净积分为0。
9. 写审计 `MEMBER_RESTORED`，记录管理员、原因和状态变化。
10. 返回完整 `MemberResponse`。

恢复语义：

- 保留历史报告、积分原始流水、冲正流水、排名记录和审计；对外净积分为0。
- `joinedAt` 不变。
- `totalPoints` 保留历史净值。
- 不恢复旧 token、旧 refresh family 或被撤销会话。
- `app_user.status` 保持原值；若账号此前被禁用，恢复成员资格不会自动启用账号。
- 恢复后再次从 `membershipStartedAt` 开始14个完整自然日观察期。

## 6. 数据与迁移

现有字段可支持状态流转：

- `member.membership_status`
- `member.membership_started_at`
- `member.removed_at`
- `member.removal_reason`
- `member.version`
- `app_user.auth_revision`
- `auth_session.revoked_at/revoke_reason`
- `audit_log`

新增 Flyway V5 仅添加查询索引：

1. `idx_report_member_activity (member_id, report_type, status, first_submitted_at)`
2. `idx_member_auto_removal (membership_status, membership_started_at)`

不修改或删除历史报告/积分原始行。清零只新增 `REVERSAL` 流水。迁移必须可重复验证，不写入真实学生数据。

## 7. 自动任务

配置：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `MEMBERSHIP_AUTO_REMOVAL_ENABLED` | `true` | 用户已确认启用 |
| `MEMBERSHIP_REMOVAL_CHECK_INTERVAL_MS` | `3600000` | 每小时检查一次 |
| `MEMBERSHIP_REMOVAL_BATCH_SIZE` | `500` | 单次最大处理数 |

要求：

- 使用注入的 `Clock` 和 `Asia/Shanghai` 日期计算。
- 启动时校验当前 Flyway 版本。
- 调度只查询候选成员，不把报告正文或个人信息写入日志。
- 每个成员独立事务；一个失败不阻止其他成员。
- 提供可测试的显式扫描方法，生产入口只调用该方法。
- 测试可通过关闭调度器并调用扫描方法控制时序。

## 8. API 契约变更

1. `/api/v1/members/{memberId}/restore` 从 `pending` 更新为 `implemented`。
2. 请求/响应遵循既有 `ReasonVersionRequest` 和 `MemberResponse`。
3. `MemberResponse.membershipStatus` 明确返回 `ACTIVE/REMOVED`。
4. 自动移出不新增前端写接口。
5. 月度评定、月度规则、月度快照、确认、执行和月度任务路由从契约中删除。
6. 月度积分查询和 `period=MONTH` 排行榜保留，仅用于查看积分，不触发淘汰。
7. 运行中 springdoc 只展示实际注册的 restore 接口；未实现的手动 remove 接口不标成 implemented。

## 9. 审计与隐私

自动移出审计详情允许保存：

- `reasonCode`
- `membershipStartedAt`
- `lastDailySubmittedAt`
- `completedInactiveDays`
- `thresholdDays=14`
- `ruleVersion=INACTIVE_DAILY_REPORT_14_COMPLETE_DAYS_V1`

禁止保存：

- 日报正文
- 学号、联系方式
- 密码、token、AI Key
- 模型响应

恢复审计保存管理员 userId、memberId、原因、版本和恢复时间。响应不包含审计详情。

## 10. 错误语义

| 场景 | HTTP | code |
| --- | ---: | --- |
| 恢复请求缺少/非法字段 | 400 | `INVALID_REQUEST` |
| 未认证 | 401 | `UNAUTHORIZED` |
| 学生/负责人调用恢复 | 403 | `FORBIDDEN` |
| 成员不存在 | 404 | `NOT_FOUND` |
| 成员已是 ACTIVE 或不是学生 | 409 | `INVALID_STATE` |
| version 不匹配 | 409 | `VERSION_CONFLICT` |
| Idempotency-Key 冲突 | 409 | `IDEMPOTENCY_CONFLICT` |
| 数据库/迁移不可用 | 503 | `SERVICE_UNAVAILABLE` |

自动任务没有 HTTP 响应，失败只记录安全审计/任务日志并在下个周期重试。

## 11. 测试计划

### 11.1 单元

- 上海日期边界：13个完整日不移出，14个完整日移出。
- 加入日/恢复日不计入完整日。
- 草稿、周报和旧周期日报不重置计时。
- 有当前周期正式日报时按最新日报重置计时。
- 连续日期中断后重新计数。
- 只有学生产生候选。

### 11.2 MySQL 集成

- 自动移出写正确状态、原因、版本和时间。
- 会话被撤销，认证版本递增。
- 只写一条审计，重复扫描幂等。
- 报告和原始积分行完全保留，新增等额冲正后总分、报告净得分和历史月份净分为0。
- 移出后迟到评分不增加积分。
- 恢复只允许管理员和 REMOVED 学生。
- 恢复重置 `membershipStartedAt`，旧日报不延长新观察期。
- 恢复幂等、版本冲突和跨请求 Key 冲突。
- 恢复时积分为0，新报告从0开始累计。
- 禁用账号恢复成员后账号仍禁用。
- V5 索引存在，无个人种子数据。

### 11.3 API/契约

- restore 请求契约、权限、错误码和完整响应。
- 运行契约与 `docs/api/openapi.yaml` 一致。
- 月度评定/淘汰路由不存在。
- 月度积分查询仍可使用且不自动移出。
- Knife4j 页面加载和真实请求调试无 JS/网络错误。

## 12. 验收标准

- 全量 `clean verify` 零失败、零错误、零跳过。
- 自动移出和恢复都有真实 MySQL 测试。
- 浏览器验证通过。
- 密钥、token、报告正文和学生个人信息扫描无泄漏。
- 文档明确取消月度评定/淘汰，明确14个完整自然日口径。
- 工作提交到 `backend` 并推送；不合并 `main`。
