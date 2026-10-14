# 学生日报不活跃自动移出与管理员恢复交付验证

日期：2026-10-11  
分支：`backend`  
规格：`docs/superpowers/specs/2026-10-11-inactivity-removal-restore-design.md`  
计划：`docs/superpowers/plans/2026-10-11-inactivity-removal-restore.md`

## 结论

已实现学生连续14个完整自然日未正式提交日报后的自动移出、幂等积分冲正、旧评分任务失效、会话撤销、审计和学院管理员恢复。月度评定、月度淘汰和月度任务已取消并从契约删除。独立审查发现的3个 Critical、5个 Important 和3个 Minor 均已修复并重新验证。

## 完整验证

执行：

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
| 单元 | 23 | 47 | 0 | 0 | 0 |
| 集成/JAR | 27 | 74 | 0 | 0 | 0 |
| 合计 | 50 | 121 | 0 | 0 | 0 |

测试使用 MySQL 8.0.46 Testcontainers 和正式 JAR，覆盖：

- 13/14个完整自然日边界，加入日和恢复日不计。
- 草稿、周报和旧在籍周期日报不重置计时。
- 自动移出状态、时间、原因、版本、认证版本和会话撤销。
- 单成员失败不会中断批次，失败审计不包含异常原文。
- 移出/恢复重复执行不重复审计、不重复冲正。
- 原始报告和积分流水保留，总分、报告净分和历史月份净分归0。
- `BASE_PLUS_AI` 重评分按 AI 净额计算，包含已有 `REVERSAL`。
- 管理员恢复的权限、状态、version、Idempotency-Key 和账号状态保持。
- 恢复后旧评分任务为 `STALE`，旧日报不延长新观察期。
- 移出后迟到评分不增加积分。
- 并发成员锁下提交新日报后，移出重新评估并保留学生。
- OpenAPI/运行契约一致，restore 已实现，月评/月度任务路径不存在。

## 独立审查修复

### Critical

1. **移出 TOCTOU**：自动移出事务按 `app_user -> member` 固定顺序加锁，锁内以 locking read 重新读取当前周期最新正式日报并重新计算14个完整自然日。报告 submit/PATCH 也先锁定成员，避免刚提交日报仍被移出。
2. **移出与评分并发**：评分落账事务先锁定成员再锁定任务；移出持有成员锁时执行任务失效和积分冲正。评分与移出串行化，移出后的提交被拒绝或转 `STALE`。
3. **恢复后旧任务加分**：移出和恢复都会把 `QUEUED/PROCESSING/RETRYING` 任务标为 `STALE`；Worker 同时校验 `firstSubmittedAt >= membershipStartedAt`，旧在籍周期任务不能进入新周期。

### Important

1. `BASE_PLUS_AI` 的 AI 差额改用 `PointLedgerService.sourceNet`，计入对应 `REVERSAL`。
2. 批量扫描逐成员隔离异常，继续处理后续成员，并写 `MEMBER_AUTO_REMOVAL_FAILED` 安全审计。
3. 恢复事务显式失效旧评分任务。
4. OpenAPI 的认证、人员、报告、积分、排行榜、自动移出/恢复和审计状态统一为 `implemented`；手动 remove 保持 `pending`。
5. 新增并发、批次失败、恢复后旧任务、积分净额和契约状态测试。

### Minor

1. 冲正事件键统一为 `MEMBERSHIP_POINTS_RESET`。
2. 删除已取消的“月度评定”OpenAPI 标签。
3. 审计时间统一使用 ISO-8601。

## Ruling 与保留范围

- “14个完整自然日”固定为上海时区已经结束的自然日；当前日期不计，规则版本 `INACTIVE_DAILY_REPORT_14_COMPLETE_DAYS_V1`。
- 自动移出只处理 `STUDENT`；方向负责人不参与。
- 管理员手动提前移出不在本期范围。
- 请假、节假日、补交和特殊豁免仍待学院确认，不自动重置计时。
- 月度评定/淘汰、月度快照/确认/执行/任务全部取消。
- `GET /system/rules` 和评分复核 HTTP 仍为后续设计项，不冒充已实现。
- 多实例分布式调度、通知渠道、数据清除期限和真实账号导入不在本次范围。

## 契约与浏览器

- `docs/api/openapi.yaml` 与 `backend/src/main/resources/api-contract.yaml` 字节一致。
- Swagger 契约测试验证已实现/待实现状态和月度路径删除。
- Playwright 真实打开 `/doc.html`，加载 `innovation-space` 分组和20个 JS/CSS 资源，点击测试探针 GET ping：

```json
{"page":"PASS","group":"innovation-space","browserPing":"PASS","status":200,"code":"OK","data":"pong","errors":[],"resourceCount":20}
```

截图和 JSON 位于 Git 忽略的 `backend/target/verification/`。

## 其他扫描

- `git diff --check`：通过。
- Maven runtime dependency tree：生成并检查，`BUILD SUCCESS`。
- 变更内容密钥扫描：未发现真实 `AI_API_KEY`、Bearer token、私钥、GitHub token 或模型 API key。
- 正式 JAR 边界仅包含 V1–V5 迁移，不含测试探针或浏览器测试类。
