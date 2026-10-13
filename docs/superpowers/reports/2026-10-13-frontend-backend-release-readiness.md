# 2026-10-13 前后端联调与上线准备报告

## 结论

工程实现、真实联调、自动化测试、生产镜像和同源反向代理冒烟均已完成。当前代码可进入学院确认和受控部署阶段，但**不建议在项目负责人完成下列业务/运维门槛前直接对公网上线**。

总体状态：`CONDITIONAL GO`。

- 代码与本地生产拓扑：`GO`
- 密钥与生成物扫描：`GO`
- 学院业务规则、真实账号交付、TLS、备份恢复、AI/OJ 合规：`OWNER GATE`

## 1. 工作基线

| 项目 | 状态 |
| --- | --- |
| 后端分支 | `refactor/layered-service-impl` |
| 后端分层 | `controller`、`service`、`service.impl`、`mapper` |
| 前端分支 | `frontend` |
| API 契约 | 前后端 OpenAPI 内容一致，前端类型已重新生成 |
| 备份 | `/home/dream/Projects/innovation-space-management-backup-2026-10-13` |
| 部署文档 | `deploy/README.md` |
| 真实联调报告 | 前端分支 `docs/frontend/2026-10-13-real-integration-report.md` |

## 2. 最终自动化验证

| 检查 | 结果 |
| --- | --- |
| `backend/mvnw clean verify` | `BUILD SUCCESS` |
| 后端单元测试 | 52 个，0 失败 |
| 后端 MySQL 集成/打包测试 | 107 个，0 失败 |
| `pnpm install --frozen-lockfile` | 通过 |
| `pnpm test` | 19 个测试文件、69 个测试全部通过 |
| `pnpm type-check` | 通过 |
| `pnpm lint` | 通过 |
| `pnpm build` | 通过 |
| Docker 后端镜像构建 | 通过，非 root 运行 |
| Docker 前端镜像构建 | 通过，非 root Nginx |
| 同源生产拓扑冒烟 | 通过 |
| Compose YAML 结构解析 | 通过 |
| 跟踪文件密钥/私钥扫描 | 未发现 |
| `node_modules/dist/target/coverage` 跟踪扫描 | 未发现 |

前端生产构建仍有一个非阻断性能提示：主入口 chunk 大于 500 kB。可在后续版本做路由级代码分割，不阻塞当前功能验收。

## 3. 真实联调覆盖

- 学生、方向负责人、学院管理员三种角色真实登录和角色首页。
- 管理员 30 天看板、负责人 7 天看板、学生 7 天看板。
- 刷新页面后重新登录，符合当前内存 Token 契约。
- 日报最小表单：学习内容必填，收获和问题选填。
- 日报创建、修改、提交、重复创建、幂等重复提交。
- Mock AI 异步评分、最终 1–3 分、保底积分和评分差额。
- 管理员直接加分，且人工积分不会破坏报告评分计算。
- 总榜、方向榜和个人排名；负责人不参与排名。
- 负责人“我的日报/周报”只显示本人记录，管理页面仍可查看所辖方向正式报告。
- 跨角色权限、非法范围、学生越权访问。
- 390×844 移动端看板布局。
- 生产 Nginx 下 SPA 深层路由、API 代理、健康检查和安全响应头。

## 4. 本轮修复

1. `AI_PROVIDER=mock` 不再错误要求真实模型名和 API Key。
2. 修复评分线程池把拒绝策略误当线程工厂的问题，后台评分任务可正常执行。
3. 管理员人工加分的空 `report_id` 流水不再破坏后续报告评分。
4. 个人日报/周报列表按当前成员过滤，避免负责人个人页面混入学生报告。
5. 同步最新日报 API 契约到后端 OpenAPI 和 Knife4j 资源。
6. 增加后端/前端生产镜像、Nginx 同源路由、Compose、部署文档和自动化冒烟脚本。

## 5. 生产部署产物

- `backend/Dockerfile`
- `frontend/Dockerfile`
- `deploy/nginx.conf`
- `deploy/docker-compose.yml`
- `deploy/.env.example`
- `deploy/README.md`
- `deploy/smoke.sh`

当前机器没有 Docker Compose v2 插件，因此本轮使用 Docker CLI 验证了等价的数据库、后端、前端网络拓扑。正式部署主机应安装 Compose v2，或按照 `deploy/README.md` 使用等价容器命令。

## 6. 上线门槛

| 门槛 | 状态 | 上线前动作 |
| --- | --- | --- |
| HTTPS、正式域名 | 未确认 | 配置 TLS、域名、防火墙和监控 |
| 管理员账号 | 未交付 | 项目负责人私密指定并执行一次管理员初始化 |
| 负责人/学生账号 | 未交付 | 管理员或负责人按真实名单添加，执行首次改密 |
| 数据库备份恢复 | 未演练 | 建立备份计划并完成一次隔离恢复演练 |
| MySQL 生命周期 | 未决策 | 确认 8.0 EOL 风险、补丁策略或升级计划 |
| 日报/周报积分规则 | 未最终确认 | 确认最终 1–3 分解释并设置 `REPORT_POINTS_POLICY_STATUS=READY` |
| AI 厂商与隐私 | 未确认 | 确认厂商、模型、Key 管理、数据发送和保存策略 |
| Judge0/OJ | 未部署 | 隔离部署、私有测试数据保护、GPL-3.0 合规审查 |
| 多实例限流 | 不适用 | 当前单实例；扩容前设计共享限流 |
| 回滚 | 未演练 | 保存上一镜像并演练数据库前滚/回退方案 |

## 7. 建议上线顺序

1. 在部署主机安装 Docker Compose v2，复制 `deploy/.env.example` 为私密 `deploy/.env`。
2. 构建并标记不可变版本镜像，不使用浮动 `latest`。
3. 启动数据库并完成迁移、管理员初始化和备份验证。
4. 先开放学院内网或测试域名，使用真实三角色验收。
5. 项目负责人确认积分、AI、账号、MySQL 和 OJ 门槛。
6. 切换 HTTPS 正式域名，启用监控告警和日志保留。
7. 保留上一版本镜像和数据库恢复点，观察至少一个完整日报周期。
