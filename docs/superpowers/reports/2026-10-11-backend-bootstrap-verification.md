# 后端基础工程验收记录

日期：2026-10-11（Asia/Shanghai）

分支：`backend`，不合并或改写 `main`

范围：已确认的后端初始化设计及五项实施任务；不是完整业务系统。

## 1. 已验证的实现

- Java 21.0.12.1 环境编译、Maven Wrapper 3.9.16、Spring Boot 4.1.1 可执行 JAR。
- MyBatis-Plus 3.5.17 和真实 MySQL 8.0.46；独立一次性容器，镜像固定摘要。
- 公共响应和参数校验；400、404、405、415、500 保留实际 HTTP 状态及必要响应头，不回显敏感字段。
- 数据库凭据必填且不提供默认密码，配置对象诊断字符串脱敏；不自动建业务表。
- 分页全局 200 上限、实际总数、第二页、较小请求上限、拒绝提高上限和负数大小回归。
- 可注入的上海业务时钟，数据库会话 UTC；健康检查原生 `UP/200` 或 `DOWN/503`，不公开详情。
- springdoc 3.1.1 / OpenAPI 3.0 / Knife4j 4.5.0 独立 UI；默认、prod 和混合 dev/prod 环境均关闭文档及实际静态资源。
- 正式 JAR 无测试探针、测试启动器、环境密钥文件、初始化 SQL 或默认管理员；正式 dev 文档 paths 为空。

## 2. 自动测试证据

实际运行 `backend/mvnw -f backend/pom.xml -B -ntp clean verify`。初次验收借助 `newgrp docker -c` 获得用户已授权的 Docker 组权限；恢复会话后该组权限已生效，直接运行 Maven。初次 44 个测试通过后，审查新增一条数据库隔离回归，最终验收额外注入 `SPRING_PROFILES_ACTIVE=dev` 与指向 `never-contact.invalid` 的 Hikari 专用连接属性，确认测试隔离不被宿主环境破坏。

| 测试阶段 | 数量 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| Surefire 单元测试 | 25 | 0 | 0 | 0 |
| Failsafe 集成测试 | 20 | 0 | 0 | 0 |
| 合计 | 45 | 0 | 0 | 0 |

结果：`BUILD SUCCESS`。集成阶段包含真实 MySQL/Mapper、数据库失败健康检查、文档环境隔离、包边界，以及通过 `java -jar` 启动正式包的默认/dev/prod/两种混合 profile 验证。测试不使用 H2，不在 Docker 缺失时隐式跳过，也不接触已有数据库。

依赖树核对：没有 Boot 3 Knife4j Starter、Boot 3 MyBatis-Plus Starter 或 H2；MyBatis/MyBatis-Spring 各一份。`git diff --check` 通过；源码及提交内容检查无真实访问令牌或私钥，凭据仅为占位符和合成测试数据。

## 3. 实际浏览器验收

使用 Playwright Chromium 加载 `/doc.html`：

- 显示“学院科创空间管理系统 API”、`innovation-space` 分组及 v1。
- 实际加载 20 个 JS/CSS 资源，未发现 JS 异常、失败请求或 HTTP 错误。
- 从页面点击“测试连通性 → 调试 → 发送”，真实 GET 请求返回 `200`，`code=OK`、`message=Success`、`data=pong`；响应内容在页面显示。
- 截图经视觉核对。临时截图和结构化结果分别为 `backend/target/verification/knife4j-dev.png`、`knife4j-browser.json`，被 Git 忽略，不进入生产包或提交。
- 可复现的可选脚本为 `backend/src/test/browser/knife4j-smoke.cjs`；Node/Playwright 浏览器验收独立于上述 45 个 Java 测试。

测试浏览器、独立应用进程和一次性数据库已停止。未停止用户的 MySQL 或 Docker 服务。

## 4. 独立代码审查

实现提交范围 `89aaebd..15e4487` 已完成一次独立、只读的整分支审查。前一次服务返回 403，未产出结论；恢复后重试成功，并不是重复审查。审查发现两个问题，未发现 Critical 问题：

1. **Important：环境中的 Hikari 专用属性可能覆盖一次性测试数据库。** 用两个独立容器复现：修复前 decoy 的 sentinel 被 `connection-init-sql` 删除，回归明确失败。修复为仅测试的 `OwnedDataSourceConfiguration`，显式创建不接受 ambient Hikari 属性重绑定的连接池；各集成上下文及浏览器启动器显式导入。执行探针 DDL/DML 前再次检查实际 JDBC URL。修复后 decoy 数据保持不变，错误环境连接也不会接管测试。正式 JAR 测试仍覆盖生产 Boot 数据源自动配置，不因测试连接池而替代生产验证。
2. **默认文档测试继承开发者 `dev` profile。** 审查原评 Minor；执行者按“照 README 启动开发后正常 verify 会失败”的实际影响提高为 Important 并修复。复现时文档资源 200 与预期 404 冲突；固定测试 `@ActiveProfiles("default")` 后，同样环境下通过。

两个问题均按 RED→GREEN 验证，并重新运行完整测试和浏览器验收。没有暂缓的 Minor 问题；未派发第二轮审查。

审查未判断的项目由执行者逐项裁定：认证/业务模块仍按批准范围延期，不代表功能已完成；版本替换及部署安全模型保持批准决定，风险继续显式记录；审查者不重复运行测试，由执行者的新一轮测试报告提供证据；未提交交付文档由执行者核对链接、数字与实际命令结果。

## 5. 执行取舍（按发生顺序）

1. 使用已有干净的 `backend` 工作目录，不再建另一份 worktree，保留用户既定工作区；代价是改动在同一 checkout 内，但提交仍限定在 backend。
2. 依赖下载中断后，仅重试时设置 60 秒读取超时，不换版本或关闭 TLS；代价是更慢的网络可能仍需重试。
3. 为官方 Wrapper 添加窄范围 `.gitattributes`，分别保留 Unix LF/Windows CRLF；代价是改变脚本检出的换行策略，不改变应用行为。
4. 提取仅测试的 `HttpTestSupport`，复用真实 HTTP 和 JSON 解析；代价是多一个测试辅助文件，无生产 API。
5. 添加 `BoundedPaginationInnerInterceptor`，在 count/溢出检查前规范化大小，防止第二页被误判和请求级上限绕过；代价是升级 MyBatis-Plus 时需维护这一小扩展。
6. 文档 Bean 和 dev YAML 使用 `dev & !prod`，混合 profile 时生产关闭策略优先；代价是混合 dev/prod 也不能查看开发文档，属于刻意的安全行为。
7. 添加空生产文档测试和真实 HTML 资源解析辅助类，不靠假 JS 路径或虚构业务路由充实文档；代价是多一个隔离容器及辅助类。
8. 添加 `PackagedApplicationIT` 自动启动正式 JAR 并检查五种 profile，比单纯手工检查更强；代价是五次本地 JVM 启动和独立测试库的运行时间。
9. 保留可选 Playwright 验收脚本，方便复现真实页面调试；代价是浏览器验收需独立的 Node/Playwright 环境，不增加生产依赖。
10. 审查修复采用仅测试的显式连接池，并增加双容器 decoy 回归和写入前 URL 检查；代价是测试池参数需要维护，生产配置另由正式 JAR 测试覆盖。
11. 审查不覆盖的业务及认证保持延期；代价是后续仍需完整开发，不能直接上线。
12. 审查不替换已确认版本或部署设计；代价是上线前仍需生命周期与安全评估。
13. 审查者不重复运行测试，验收由执行者执行并核对；代价是执行证据不是第二人的复跑。
14. 审查范围外的交付文档由执行者核对；代价是这些文字不是独立审查结论。

## 6. 后续工作与上线限制

尚未实现认证/方向授权、人员 CRUD、日报周报、AI 评分、积分流水及排行榜、月度评定和连续两周未交日报的自动移出。不得将基础工程当作已交付的完整管理系统。**学生自注册禁止**，后续学生账号只能由方向负责人或学院管理员添加。

MySQL 8.0 生命周期风险已在批准设计和启动说明中记录；上线前重新评估受支持版本、安全更新和升级方案。当前默认仅监听本机，文档关闭不是权限系统，生产发布仍需认证、TLS、密钥管理、最小数据库权限及业务审计。

启动方式和环境变量见 `backend/README.md`；业务规则见 `docs/requirements.md`。
