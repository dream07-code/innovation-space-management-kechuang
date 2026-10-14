# OJ 考核系统交付验证报告

日期：2026-10-12

分支：`backend`

OJ 范围基线：`74cd50c`

验证头：`2403e26` 加本轮前端契约/验证文档修正
计划：`docs/superpowers/plans/2026-10-12-oj-assessment-system.md`

## 1. 交付范围

本轮交付独立于日报/周报积分的 OJ 考核领域：

- 考核、题库、代码提交、OJ 判定、AI 评分、人工 review 和独立结果 API。
- `Assessment/Problem/Submission/OjJudgeResult/AiEvaluation/Review/Result` 状态与 Flyway V6/V7 数据模型。
- `OjAdapter` 隔离、Mock OJ、Judge0 Community Edition 服务端适配器。
- Mock 与 OpenAI-compatible AI Provider；人工 review 覆盖 AI，迟到 AI 不覆盖人工结果。
- 学生/方向负责人/学院管理员权限、Idempotency-Key、代码/测试数据/Token/原始 Provider 输出保护。
- OJ 结果不写 `point_ledger`，不改变日报/周报积分或排行榜。
- 前端 planned DTO、Mock 状态、错误码和角色矩阵已写入 OpenAPI 的 `x-frontend-contract`。

## 2. 前端契约同步

- `docs/api/openapi.yaml` 与 `backend/src/main/resources/api-contract.yaml` 字节一致，SHA-256 均为 `08458ff56aba224dd76884e5bd8c8ba94d6042c7eb56531b75d2c05955d4f6d2`。
- OpenAPI 3.0.3 规范校验通过；73 个请求/响应示例通过 OAS 3.0 schema 校验。
- OJ 路径继续标记 `planned`，前端可以生成类型、Mock、状态页和错误处理，不依赖后端 OJ 私有字段。
- ID 均为十进制字符串；普通响应禁止 `code/codeText/codeLocator/privateTestData/rawProviderResponse/apiToken/password`。
- `backend/README.md` 增加 OJ/AI Provider、角色、Mock 和真实 Judge0 启用边界。

## 3. 自动验证结果

| 检查 | 命令/方法 | 结果 |
| --- | --- | --- |
| 全量构建 | `backend/mvnw -f backend/pom.xml -B -ntp clean verify` | 50 个单元测试、92 个真实 MySQL 集成/打包测试，0 failure/error |
| 契约测试 | `ImplementedApiContractIT`, `OJApiContractIT` | 2/2 通过 |
| OpenAPI 规范 | `openapi-spec-validator docs/api/openapi.yaml` | `OK` |
| 示例校验 | OAS30ReadValidator/OAS30WriteValidator | 73 个示例通过 |
| 契约副本 | `cmp` + SHA-256 | 字节一致 |
| 依赖树 | `mvn dependency:tree -Dverbose` | 316 行输出，构建成功 |
| 密钥扫描 | 对 222 个 tracked 文件扫描私钥、AWS/Google/GitHub/Slack/OpenAI 风格 Token | 未发现真实硬编码密钥；合成/示例值排除 |
| Knife4j 浏览器 | Playwright 真实点击 `/doc.html` 测试探针 | `page=PASS`, `browserPing=PASS`, HTTP 200, `data=pong`, 0 JS/网络错误, 20 个资源 |
| diff 检查 | `git diff --check` | 通过 |

测试日志中的 Mock 自附加警告和预期的 404/403/503 探针警告不是测试失败。Knife4j 产物位于 `backend/target/verification/`，属于忽略的构建目录。

## 4. 安全与评分边界

1. 学员代码保存在受控 `assessment_submission_code`，普通响应只给 SHA-256 `codeHash` 和安全 OJ 摘要。
2. 私有测试数据/输入输出不进入题目响应；Judge0 原始 `stdout/stderr` 和 Provider 原始 JSON 永不持久化到普通响应或审计。
3. `Judge0OjProperties` 对 Token/URL 做脱敏；API Key 仅从环境变量注入。
4. OJ 超时、编译/运行/内存错误映射稳定 verdict；网络、畸形响应、未知状态和缺少测试用例映射 `INTERNAL_ERROR`，不伪造通过或 0 分。
5. 人工 review 要求 `submissionVersion/reviewVersion/score/comment` 和 Idempotency-Key；历史 review 可追溯，仅一个 ACTIVE。
6. AI 分数独立为 0–100；人工分数优先。AI PENDING/FAILED 时 `finalScore=null`，`scoreSource=PENDING/UNAVAILABLE`。
7. OJ/AI/review/result 全链路不写 `point_ledger`。

## 5. 独立审查

本次按 read-only 全分支审查检查了 OJ 沙箱边界、代码隐私、状态机、OpenAPI、幂等/版本冲突、评分优先级、迁移和测试真实性。由于用户未授权派发子代理，本轮为作者自审，弱于全新上下文审查；未发现 Critical 或需要在本计划内立即修复的 Important 缺陷。

审查确认：

- V6/V7 迁移无个人种子数据，JAR 只包含生产迁移和类。
- 考核状态、题目状态、提交状态、review 状态与结果状态没有绕过版本/权限检查的普通 HTTP 路径。
- 代码、测试结果和模型/Token 不进入普通响应、日志或审计。
- AI/人工优先级测试覆盖迟到 AI 和 FAILED AI。
- 结果接口验证不增加积分流水。

## 6. 部署前置条件与范围外事项

以下属于部署/规模阶段，不作为本计划内代码缺陷：

- 真实 Judge0 必须部署到隔离网络，提供受控私有测试用例加载/导入流程，并完成 GPL-3.0 合规审查；未满足前保持 `OJ_PROVIDER=mock`。
- 多实例部署需要把 AI 评估状态转换从当前单进程序列化升级为数据库锁或唯一任务表。
- Judge0 语言 ID、资源限制、代码/测试数据保留期需按实际镜像和学院政策验收。
- 公网部署仍需 TLS、最小权限、密钥管理、备份恢复和受支持的 MySQL 版本评估。

## 7. 接受矩阵

| 要求 | 证据 | 结论 |
| --- | --- | --- |
| OJ 可替换 | `OjAdapter`、Mock/Judge0 adapter、`OpenSourceOjAdapterIT` | 通过 |
| 学生代码安全 | Submission/Evaluation privacy IT、secret scan | 通过 |
| OJ verdict | Judge0 fake contract 状态矩阵 | 通过 |
| AI scoring | `AssessmentEvaluationHttpIT`、OpenAI-compatible provider | 通过 |
| Human review precedence | `AssessmentReviewHttpIT`、迟到 AI 测试 | 通过 |
| Independent result | `AssessmentResultHttpIT` 的 point ledger 断言 | 通过 |
| Frontend parallel | `x-frontend-contract`、planned DTO、Mock 规则 | 通过 |
| Role permissions | Management/Submission/Evaluation/Review IT | 通过 |
| Real OJ integration | Judge0 选型报告、本地 fake HTTP adapter tests | 适配层通过；生产启用待部署前置条件 |

## 8. 结论

后端 OJ 考核功能、契约、适配层、AI/人工评分和全量验证已完成，可以推送 `backend` 并供前端同步开发。真实 Judge0 和多实例生产启用需按第6节完成部署验收，不应仅凭本报告直接开放公网或执行不可信代码。
