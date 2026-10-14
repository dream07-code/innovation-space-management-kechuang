# 开源 OJ 选型报告

日期：2026-10-12

范围：`backend` 分支 OJ 考核系统的执行引擎适配层
数据来源：各项目 GitHub 仓库/API 页面（本次访问时间：2026-10-12）；许可证和部署条件在正式上线前仍需由学院完成合规审查。

## 结论

选择 **Judge0 Community Edition（judge0/judge0）** 作为首个受控代码执行引擎，通过 `Judge0OjAdapter` 接入。学院后端继续拥有考核、题目、提交、评分和权限模型；Judge0 只接收一次受控的代码/测试用例执行请求，不接收学院身份数据，也不向浏览器暴露服务地址、API Token、原始 stdout/stderr 或测试数据。

Judge0 适配层的测试用例通过 `OjSubmission.TestCase(input, expectedOutput)` 传递。真实部署必须由受控测试数据加载器提供这些用例；加载器和 OJ 服务都只能在服务端网络内访问。当前默认 `app.oj.provider=mock`，因此未配置 Judge0 时不会访问外部网络。

## 候选比较

| 项目 | 许可证 | 维护/活跃度（观察） | 语言与沙箱 | Docker/API | 测试数据模型 | Java 集成与安全边界 | 结论 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| [judge0/judge0](https://github.com/judge0/judge0) | GPL-3.0 | 2026-10-08 仍有推送；约 4.4k stars | 官方说明支持 90+ 语言；沙箱编译/执行 | 官方 Docker/自托管；HTTP JSON API | 由调用方传入 `stdin`/`expected_output`，适合本系统受控测试用例 | REST 适配简单；服务端隔离 Token；不把身份同步给 OJ | **首选**：执行引擎清晰，最容易隔离私有测试数据 |
| [hydro-dev/Hydro](https://github.com/hydro-dev/Hydro) | AGPL-3.0 | 2026-10-09 仍有推送；约 7.1k stars | 完整 OJ 平台，支持多语言/评测 | Docker/部署体系完整 | 题目、测试数据、评测均在平台内 | 功能最完整但平台耦合更强；AGPL 对衍生服务披露要求需审查 | 备选：若学院要直接采用完整 OJ 平台 |
| [DMOJ/online-judge](https://github.com/DMOJ/online-judge) | AGPL-3.0 | 2026-10-11 仍有推送；约 1.2k stars | 成熟竞赛 OJ；评测能力完整 | 可部署；集成面较大 | 题库/测试数据/评测在平台内 | 需要同步更多平台概念；AGPL 合规需审查 | 备选：偏竞赛平台，不适合最小隔离适配 |
| [syzoj/syzoj](https://github.com/syzoj/syzoj) | MIT | 最近一次推送约 2023-08；约 0.9k stars | 算法竞赛 OJ | 项目自身可部署 | 题库/测试数据/评测 | 许可证宽松，但维护和依赖时效风险较高 | 不选：维护活跃度不足 |

## Judge0 集成边界

1. `Judge0OjAdapter` 只负责把 `OjSubmission` 的语言、代码和私有测试用例转换成 Judge0 提交请求。
2. 请求使用本地/内网 `base_url`，可选 `X-Auth-Token`；API Token 仅存在服务端配置，不出现在 OpenAPI、响应、日志或审计。
3. Judge0 状态映射为稳定的 `OjVerdict`，只返回通过数、总测试数、耗时、内存和安全状态描述；原始 stdout/stderr 永不进入普通响应。
4. 网络超时、非 2xx、响应过大、JSON 畸形、未知状态和无测试用例均映射 `INTERNAL_ERROR`，不伪造通过或 0 分。
5. 适配器默认不启用。启用前应完成 Judge0 Docker 部署、网络隔离、资源限制、测试数据备份和 GPL-3.0 合规评估。

## 部署建议

- 将 Judge0 部署在学院内网 Docker 主机或隔离测试集群，禁止浏览器直接访问。
- 通过 `OJ_PROVIDER=judge0`、`OJ_BASE_URL`、`OJ_API_TOKEN` 注入服务端配置；不要把 Token 写入仓库。
- 先用本地 fake contract tests 验证适配器，再用非生产题库验证 Java/Python/C++ 的编译、超时、内存和运行错误映射。
- 代码、测试用例和 Judge0 原始输出按最小权限保存；当前 API 契约只公开安全 OJ 摘要。
