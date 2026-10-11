# 日报/周报、可配置 AI 评分、积分与排行榜技术设计

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-11 |
| 版本 | v1.0 |
| 状态 | 待用户审阅 |
| 分支 | `backend` |
| 关联认证设计 | `docs/superpowers/specs/2026-10-11-auth-member-management-design.md` |
| 关联 API 契约 | `main@37797e4` 的 `docs/api/openapi.yaml` |
| 业务时区 | `Asia/Shanghai` |

## 1. 目标与范围

本阶段实现：

- 学生/方向负责人创建日报、周报草稿并提交。
- 报告版本、提交状态、评分状态和评分理由可追踪。
- 报告提交、AI 评分、人工差额调整使用积分流水，不直接修改累计总分。
- AI 评分采用可配置厂商/地址/模型/Key，业务层不绑定厂商 SDK。
- 异步评分、有限重试、失败转人工复核；模型 Key 不进入浏览器、数据库、日志或审计详情。
- 查询本人/授权范围报告、积分流水、个人排名、总榜和方向榜。

本阶段不实现：

- 月度末位评定、月度快照、自动移出和恢复。
- 连续两周未提交日报任务。
- 具体淘汰人数、同分淘汰规则、请假/补交政策。
- 公开直接加减积分、物理删除报告、修改管理员密码的 HTTP 接口。

## 2. 已确认规则与未决规则

### 2.1 本阶段固定的技术行为

- 草稿不计分，不重置日报未提交计时。
- 每位成员每天最多一份日报、每个自然周最多一份周报；重复创建返回 409。
- 提交成功与 AI 评分完成分离；提交失败不能产生积分，评分失败不能撤销已成功提交。
- 同一报告版本重复提交、重复评分回调或重复任务不能重复入账。
- 报告正文作为不可信输入；发送给模型时默认不带姓名、学号、联系方式、角色、方向负责人信息。
- AI 返回必须是整数 `1..3` 与短理由；非法 JSON、越界分数、超时、网络错误进入重试/复核，不得直接入账非法分数。
- 老报告版本的迟到评分不能覆盖当前有效版本；人工确认结果优先于迟到 AI 结果。
- 报告详情中的 `finalScore` 未完成时为 `null`，不能在前端渲染成 0；`awardedPoints` 是已入账的真实流水净值。
- 积分榜只显示最小必要字段，不返回学号、联系方式、报告正文、模型原文或 API Key。

### 2.2 可配置但不能隐式决定的业务策略

原需求存在“最低 1 分、AI 评 1–3 分做积分叠加”的歧义。实现不把答案埋在代码中，使用明确配置策略：

```text
FINAL_SCORE: 正式提交保底 1 分，最终总分为 AI/人工确认的 1–3 分，只补差额。
BASE_PLUS_AI: 正式提交基础分与评分分分别入账，AI 分数按学院确认的规则计算。
```

生产环境默认 `PENDING_CONFIRMATION`，未配置/未确认时：

- 草稿可以保存。
- 正式提交可以由部署方选择“暂不接受”或“提交但只入保底分”；这一点必须由实现配置明确，不能自行判断。
- 月度功能永远不因本设计自动启用。

开发集成测试可使用合成 `FINAL_SCORE` 规则和 fake provider；这不是学院生产规则的确认。

## 3. AI 厂商与 Key 配置

### 3.1 统一配置项

配置只从环境变量、部署密钥或外部配置中心注入；不从前端请求接收、不写数据库、不写 `.env` 示例实际值：

| 配置 | 说明 |
| --- | --- |
| `AI_SCORING_ENABLED` | `false`/`true`，默认 `false` |
| `AI_PROVIDER` | `disabled`、`mock`、`openai-compatible` 或未来注册的 provider code |
| `AI_BASE_URL` | 厂商 API origin，禁止由报告正文/请求参数覆盖 |
| `AI_API_KEY` | 必填私密值；只在 provider 内存中使用，不进入日志/异常/审计 |
| `AI_MODEL` | 厂商模型标识 |
| `AI_CONNECT_TIMEOUT_MS` | 连接超时，受 100–30000 范围限制 |
| `AI_REQUEST_TIMEOUT_MS` | 请求超时，受 1000–120000 范围限制 |
| `AI_MAX_RETRIES` | 0–3，指数退避，不能无限重试 |
| `AI_MAX_CONCURRENCY` | 单实例评分并发上限，默认小值 |
| `REPORT_POINTS_POLICY_STATUS` | `PENDING_CONFIRMATION`/`READY` |
| `REPORT_POINTS_MODE` | `FINAL_SCORE`/`BASE_PLUS_AI`，只有 READY 时生效 |

Key 只能在 provider 调用边界形成 `Authorization` 请求头。日志只记录 provider code、model、任务 ID、耗时、状态和脱敏错误分类；不记录 URL query、Authorization、完整请求体、完整模型响应或报告正文。

### 3.2 Provider SPI

```java
public interface ReportScoringProvider {
    ProviderScore score(ScoringInput input);
}

public record ScoringInput(
    ReportType reportType,
    String reportVersion,
    String rubricVersion,
    String content
) {}

public record ProviderScore(int score, String reason, String provider, String model) {}
```

- `MockReportScoringProvider`：仅测试/开发 profile，返回确定性合成结果，不接收真实 Key。
- `OpenAiCompatibleReportScoringProvider`：使用 JDK HTTP client 调用可配置 `AI_BASE_URL`，请求/响应通过 DTO 严格解析；不依赖具体厂商 SDK。
- 未来厂商只需新增 provider 实现和配置绑定，不改变报告/积分表及 API。
- Provider 不负责写报告或积分；评分任务服务负责版本检查、重试、状态和流水。
- `AI_SCORING_ENABLED=false` 时不创建后台调用；提交是否只入保底分由积分策略状态明确决定，禁止静默“假评分”。

### 3.3 Prompt 与模型输出

评分规则在服务端维护版本化 rubric 文本。报告正文放在不可信输入区，必须明确分隔，模型指令要求只返回：

```json
{"score": 1, "reason": "不超过 300 字的简短理由"}
```

后端校验：`score` 必须是整数 1–3，`reason` 非空且长度受限；额外字段可拒绝或忽略，但不能当成执行指令。模型不能访问数据库、URL、工具或修改权限。

## 4. 报告状态和数据模型

### 4.1 `report`

一张表用 `report_type=DAILY/WEEKLY` 区分日报/周报，或等价的两张表实现；本设计推荐一张表便于统一版本和评分任务。

字段约束：

- `id`、`member_id`、`direction_id_at_submission`、`report_type`。
- 日报 `report_date`；周报 `week_start`、`week_end`。
- `status=DRAFT/SUBMITTED`；`version`；`content_json` 或规范化正文列。
- `first_submitted_at`、`updated_at`、`editable_until`。
- 当前有效 `score_status`、`final_score` 可空、`score_reason` 可空、`score_version`、`rubric_version`。
- `awarded_points` 只作为查询缓存/校验值，真实总分以 point ledger 重算；禁止业务接口直接更新。
- 约束：成员+类型+周期唯一；日期由服务端 `Asia/Shanghai` 计算，不能使用客户端浏览器时区。
- 草稿允许正文不完整；submit 时校验日报 `learningContent/gains`、周报 `summary/achievements` 合并后的结果非空白。
- 已提交 PATCH 必须先合并再校验；非法编辑 400 且报告、积分、任务均不变。

版本可以采用 `report_version` 历史表，推荐保留每个正式版本的正文 hash、正文、编辑者、时间和是否当前版本；模型评分只引用具体版本 ID，避免迟到回调误更新。

### 4.2 `report_scoring_task`

- `id`、`report_id`、`report_version_id`、`status=QUEUED/PROCESSING/SCORED/RETRYING/REVIEW_REQUIRED/FAILED/STALE`。
- provider、model、rubric_version、attempt、next_retry_at、started_at、finished_at、safe_error_code、score、reason。
- 同一报告版本的活动评分任务唯一；回调/重试以 task/version 唯一键幂等。
- 不保存原始 Key、Authorization、完整 prompt 或完整 provider response；如需诊断只保存脱敏摘要和 hash。
- 任务领取采用数据库锁/状态条件更新，多个实例不得同时处理同一任务。

### 4.3 `point_ledger`

- `id`、`member_id`、`report_id`、`report_version_id`、`delta`、`source`、`business_effective_at`、`recorded_at`、`rule_version`、`idempotency_key`。
- source：`SUBMISSION_BASE`、`AI_ADJUSTMENT`、`MANUAL_ADJUSTMENT`、`REVERSAL`。
- 每个业务事件有唯一去重键；重试不能重复入账。
- 当前累计分为流水净和；报告查询的 `awardedPoints` 与流水净和交叉校验。
- AI 从 1 调整到 3 只写 `+2`；从 3 调整到 1 写 `-2`；不删除旧流水。
- 月份归属按报告首次正式提交业务时间保存，不按评分回调时间漂移；月度结算尚未实现。

### 4.4 `report_review`

本阶段只建立可供未来人工复核使用的结构：报告版本、原分、人工分、理由、处理人、版本、状态和时间。HTTP 复核接口可以在本阶段暂不开放；评分失败先进入 `REVIEW_REQUIRED`，不伪造人工结果。

## 5. 提交、评分和积分事务

### 5.1 创建草稿

1. 从 SessionPrincipal 取得当前成员，不接受客户端 memberId 作为身份来源。
2. 校验成员 ACTIVE/账号 ENABLED 和日期窗口。
3. 对 `(member_id, report_type, period)` 做唯一检查并依赖数据库唯一键。
4. 创建 DRAFT，内容可不完整，积分为 0，scoreStatus=NOT_REQUESTED。
5. POST 写操作要求 Idempotency-Key；重复请求回放第一次结果，不创建第二份报告。

### 5.2 正式提交

事务内：

1. 锁报告当前版本、成员在籍状态和规则版本。
2. 合并当前内容并执行正式校验。
3. 如果已 SUBMITTED 且 version 相同，返回现状，不重复积分。
4. 写正式版本、状态 SUBMITTED 和首次提交时间。
5. 按已配置策略写入一次 `SUBMISSION_BASE`，或策略未 READY 时返回 `RULES_NOT_CONFIGURED`；不能没有明确策略就猜测。
6. 创建唯一评分任务 QUEUED；任务创建失败时整个提交事务可恢复，不产生孤立积分。

HTTP 200 表示提交事务完成，不表示 AI 已评分。返回当前报告状态、`finalScore=null` 或已有人工/AI结果和 `awardedPoints`。

### 5.3 Worker 评分

- Worker 批量领取 QUEUED/RETRYING，使用短事务锁定任务后改 PROCESSING，锁外调用 provider。
- 调用前从内存配置读取 provider，按 connect/request timeout 执行；Key 不进入任务表。
- 成功：校验 score/reason；再次锁定 task/report version，若不是当前有效版本或已有 MANUAL 则写 STALE；否则写 SCORED、理由和 provider metadata，并在同一事务追加积分差额。
- 失败：分类为 timeout/network/5xx/invalid_response；未超过重试次数进入 RETRYING，超过进入 REVIEW_REQUIRED，已提交报告和已有保底积分不回滚。
- 任务重试使用唯一事件键；重复 worker 不重复补分。
- provider 不可用或配置缺失时，状态转 REVIEW_REQUIRED/FAILED，明确错误码，不向学生返回 Key 或内部地址。

## 6. 查询和权限

- 报告列表先 SQL 做角色/方向范围过滤，再 count/page；学生默认本人，负责人本人+授权方向正式报告，管理员只读正式报告，不展示他人草稿。
- 报告详情对作者、授权负责人、管理员按对象级权限校验；ID 不存在与越权可统一404。
- 学生/负责人可以看到本人完整正文和评分理由；管理员按权限查看；排行榜不展示正文、学号和联系方式。
- point ledger 只读，学生默认本人，负责人授权范围，管理员按筛选权限；没有直接写积分 API。
- 排行榜使用 `SUM(point_ledger.delta)` 或可验证缓存；默认只包含 ACTIVE 成员。
- 总榜和方向榜支持 `ALL_TIME/MONTH`，同分竞赛排名 1、1、3，稳定展示按 memberId 数值排序但不影响名次。

## 7. 运行配置和降级

- `AI_SCORING_ENABLED=false` 是安全默认；没有 provider/key/model 不会启动外部调用。
- 配置变更只影响新领取任务；正在调用的任务使用已加载的 provider 快照，不在调用中途更换 Key。
- 每次启动做配置格式校验：enabled时 provider、baseUrl、model、key 完整；baseUrl 必须 HTTPS（本地测试允许 loopback HTTP）；不允许 URL 中携带 key。
- 失败降级是“报告仍然已提交、保底策略按配置处理、任务进入重试/人工复核”，不是把评分当 0 分，也不是阻止学生查看已提交状态。
- Key 轮换不写数据库；旧任务在内存 provider snapshot 中完成/失败，后续任务用新配置。日志只输出 provider code/model/安全错误类别。
- 未来若配置多个 provider，使用 provider registry 和明确路由策略；不自动在失败时把报告转发给未知第三方。

## 8. 测试和验收

### 8.1 单元

- 日报/周报周期唯一、草稿/提交校验、合并 PATCH 后非空校验、版本冲突。
- Provider 配置校验：disabled 不要求 key；enabled 缺 key/base/model 失败；loopback HTTP 仅测试允许，公网必须 HTTPS。
- Mock provider 返回 1/2/3、越界、非法 JSON、超时/5xx；worker 重试和最终 REVIEW_REQUIRED。
- 迟到旧版本、人工状态、重复 task/event 不改当前结果、不重复流水。
- 积分差额、流水净和、同分竞赛排名、月份归属。
- Key 不在异常、日志、任务、响应、审计中。

### 8.2 MySQL 集成

- 受控迁移只创建本阶段 report/task/review/ledger 表和必要索引，不插真实数据。
- 同一成员同一天/周第二次创建409；重复submit/worker/retry不重复积分。
- AI成功、失败、超时、非法返回和人工复核状态正确；旧版本结果不覆盖当前。
- 报告/流水/任务事务崩溃恢复；报告已提交而AI失败仍可查询。
- 方向范围、草稿可见性、排行榜最小字段和分页total正确。

### 8.3 外部调用边界

- 测试只使用loopback fake HTTP provider或mock provider，不访问真实厂商，不使用真实Key。
- fake provider断言请求只包含报告类型/版本/规则/正文，不包含姓名、学号、联系方式或token。
- staged文件扫描没有 `AI_API_KEY` 实际值、Authorization、provider响应原文或报告正文。

## 9. 非目标和风险

- 本阶段不启用月度淘汰、不启用两周自动移出；没有规则配置时相关操作不存在或返回 `RULES_NOT_CONFIGURED`。
- 外部模型是否允许接收报告正文、厂商保存策略、预算和通知渠道仍需学院确认。
- AI provider是可配置扩展点，不承诺所有厂商的私有协议都兼容；非 OpenAI-compatible 厂商需要新增适配器。
- 不把模型评分当作事实或权限来源；最终积分仅由后端校验后的流水决定。
