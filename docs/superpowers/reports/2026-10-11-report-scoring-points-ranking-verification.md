# 日报周报、评分、积分与排行榜交付验证

日期：2026-10-11  
分支：`backend`  
设计：`docs/superpowers/specs/2026-10-11-report-scoring-points-ranking-design.md`  
计划：`docs/superpowers/plans/2026-10-11-report-scoring-points-ranking.md`

## 结论

当前范围内的日报/周报、可配置评分、积分流水和排行榜功能已完成并通过真实 MySQL、正式 JAR、契约及 Knife4j 浏览器验证。月度评定、月度末位淘汰、两周未提交自动移出、移出/恢复和公开人工复核接口仍等待学院业务规则确认，本阶段没有擅自启用。

## 完整验证

从仓库根目录执行：

```bash
SPRING_PROFILES_ACTIVE=dev \
SPRING_DATASOURCE_HIKARI_JDBC_URL='jdbc:mysql://never-contact.invalid:1/decoy' \
SPRING_DATASOURCE_HIKARI_USERNAME=decoy \
SPRING_DATASOURCE_HIKARI_PASSWORD=decoy \
backend/mvnw -f backend/pom.xml -B -ntp clean verify
```

结果：`BUILD SUCCESS`。

| 范围 | 类 | 测试 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: | ---: |
| 单元 | 21 | 44 | 0 | 0 | 0 |
| 集成/JAR | 24 | 67 | 0 | 0 | 0 |
| 合计 | 45 | 111 | 0 | 0 | 0 |

集成测试使用固定摘要的 MySQL 8.0.46 Testcontainers，不使用 H2，不静默跳过。测试覆盖 V1–V4 迁移、外键、报告周期和唯一性、PATCH 合并校验、提交幂等、角色范围、评分成功/非法响应/超时/500、重试/人工复核、租约恢复、旧版本/人工优先、重评分差额、跨月业务生效时间、两种积分模式、排行榜同分/月份/PII、成员积分展示、契约和正式 JAR。

## 契约与浏览器

- `docs/api/openapi.yaml` 与 `backend/src/main/resources/api-contract.yaml` 字节一致。
- `ImplementedApiContractIT` 使用 Swagger 解析契约并检查生成 OpenAPI 的报告、积分、排行榜路径与响应 schema；月评、移出等未实现路径没有被标成可用。
- 正式 JAR 在 default/dev/prod 的文档开关、JAR 排除、启动和健康行为由 `PackagedApplicationIT`/文档集成测试覆盖。
- Playwright 真实打开 `/doc.html`，加载 `innovation-space` 分组和 20 个 JS/CSS 资源，点击测试探针 GET ping：

```json
{"page":"PASS","group":"innovation-space","browserPing":"PASS","status":200,"code":"OK","data":"pong","errors":[],"resourceCount":20}
```

截图与结果位于 Git 忽略的 `backend/target/verification/`。

## 配置与安全边界

- `AI_SCORING_ENABLED=false` 是默认值；未启用时不调用外部服务。
- 支持 `mock` 和 `openai-compatible`。厂商、Base URL、Model、Key、超时、重试、并发和轮询间隔均由外部配置注入。
- 公共 Base URL 只能是干净 HTTPS origin；loopback 测试可使用 HTTP。拒绝 userinfo、query、fragment 和非根 path。
- 外部响应最多读取 1 MiB；provider 响应必须是 1–3 分和受限理由。
- 系统 rubric 作为 system message，报告正文作为明确标记的不可信 user 数据。Key 只形成 `Authorization` 请求头，不进入 Git、数据库、任务、日志、审计或响应。
- 报告正文仅保存在 `report_version.content_json`，不写任务、日志或模型响应记录。
- 积分只能通过唯一事件流水产生；重评分写净差额，业务生效时间取首次正式提交时间。
- `FINAL_SCORE` 与 `BASE_PLUS_AI` 都有实现和测试；生产启用前仍需学院确认最终规则及 `REPORT_MAX_FINAL_SCORE`。

## 独立整分支审查

独立审查初始结论为不可合并：3 个 Critical、10 个 Important、4 个 Minor。以下问题已全部修复并以 RED→GREEN 或回归测试验证：

1. 重评分错误地按保底分重复补分。
2. AI 补分使用回调时间，导致跨月归属漂移。
3. 外部请求未携带实际 rubric。
4. 允许跨日/跨周补交和永久编辑草稿。
5. `MANUAL` 报告仍可被修改并重排队。
6. `PROCESSING` 崩溃任务永久卡住，失败覆盖旧版本状态。
7. submit 的 Idempotency-Key、创建日期字段和契约不一致。
8. 硬编码日期、无效 FK SQL、provider 异常矩阵不足。
9. AI URL/超时/并发和响应大小缺少边界。
10. 管理员空 memberId 导致 500。
11. 文档状态互相矛盾。
12. 积分策略只支持一半模式且缺少上限校验。
13. 成员接口固定返回 0 积分。
14. 唯一键错误码、无操作 PATCH、历史方向筛选和文档空行问题。

审查中明确排除且继续等待业务确认的范围：月评/淘汰人数与同分保护、14 天自动移出与请假口径、移出/恢复政策、公开人工复核、直接加减积分、通知渠道、多方向成员、非 OpenAI-compatible 私有 SDK、多实例分布式调度、TLS/密钥管理服务和真实账号导入。

## 其他扫描

- `git diff --check`：通过。
- Maven runtime dependency tree：生成并检查，`BUILD SUCCESS`。
- 变更内容密钥扫描：未发现真实 `AI_API_KEY`、Bearer token、私钥、GitHub token 或模型 API key。
- 测试 provider 仅使用 loopback fake server 和合成 Key；没有外部模型网络调用。
