# 学院管理员直接增加学生积分验证

日期：2026-10-12  
分支：`backend`  
接口：`POST /api/v1/members/{memberId}/points`

## 已确认行为

- 仅学院管理员可调用。
- 请求只包含正整数 `points`。
- 不要求原因、version 或 Idempotency-Key。
- 每次调用均新增一条 `MANUAL_ADJUSTMENT` 积分流水并累计。
- 不支持减分；不能直接修改累计总分字段。
- 可向 `ACTIVE` 或 `REMOVED` 学生加分，方向负责人和其他角色拒绝。
- 返回更新后的完整 `MemberResponse`。

## 验证结果

完整命令：

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
| 集成/JAR | 28 | 76 | 0 | 0 | 0 |
| 合计 | 51 | 123 | 0 | 0 | 0 |

重点测试覆盖：

- 管理员连续加分3和2，总分正确累计为5。
- 每次调用生成一条 `MANUAL_ADJUSTMENT` 流水和一条 `POINTS_ADDED` 审计。
- 学生和方向负责人调用返回403。
- `0`、负数、小数、未知字段返回400。
- 不存在成员返回404，方向负责人返回409。
- 已移出学生仍可直接加分。
- 实际响应通过 `MemberResponse` 契约校验。

## 契约与浏览器

- `docs/api/openapi.yaml` 与 `backend/src/main/resources/api-contract.yaml` 字节一致。
- `AddMemberPointsRequest` 只接受 `points`，范围1至2147483647。
- Knife4j Playwright 验证通过：

```json
{"page":"PASS","group":"innovation-space","browserPing":"PASS","status":200,"code":"OK","data":"pong","errors":[],"resourceCount":20}
```

## 其他扫描

- `git diff --check`：通过。
- Maven runtime dependency tree：通过。
- 密钥、Bearer token、私钥扫描：未发现真实凭据。
