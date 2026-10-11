# 认证与人员管理验收记录

日期：2026-10-11，Asia/Shanghai。分支：`backend`。

## 已实现范围

本阶段继承Java21/Boot4.1.1/MyBatis-Plus3.5.17/MySQL8.0.46基础工程：

- Flyway12.4.0受控迁移，V1–V3基础表、七方向和bootstrap互斥记录；默认Web启动不自动建表，未迁移库DOWN/503，业务返回SERVICE_UNAVAILABLE。
- 学号/账号登录、首次受限改密、随机Bearer会话、refresh轮换/重放家族撤销、退出和即时撤销。
- PBKDF2-HMAC-SHA256，16字节盐、600000迭代、256位输出；密码和原始token不存数据库明文。
- 七方向查询、SQL范围过滤分页、学生添加/详情/PATCH、管理员负责人任命/维护/撤销。
- 管理员学号更正、密码重置、脱敏审计；member/user ID不变，无公开注册或管理员HTTP开户。
- 非HTTP管理员初始化：仅私密部署环境输入，不覆盖已有管理员，不在正常启动隐式创建账号。
- 运行中Knife4j只列已注册路径，以审阅契约提供DTO描述；实际HTTP响应通过独立ContractAssertions校验。

尚未实现日报、周报、AI、积分、排行、月评、自动移出；`remove/restore`未注册，默认成员总分0不代表已有积分实现。未写入真实管理员/学生/负责人数据，没有运行前端工程。

## 验证证据

审查前串行执行 `backend/mvnw -f backend/pom.xml -B -ntp clean verify`，额外注入shell dev profile及指向never-contact.invalid的Hikari专用属性：

| 阶段 | 测试 | 失败 | 错误 | 跳过 |
| --- | ---: | ---: | ---: | ---: |
| Surefire | 34 | 0 | 0 | 0 |
| Failsafe | 40 | 0 | 0 | 0 |

结果BUILD SUCCESS。任务1–6分别通过task-done验证。包括真实MySQL、独立decoy、防篡改测试、正式JAR五profile、迁移维护命令和初始化命令，未用H2或隐式跳过。

浏览器再验收：测试专用隔离链仅开放test-probe，不进入生产包。Knife4j页面加载20个JS/CSS资源，实际点击发送ping，200/OK/pong，无JS或网络错误；截图已视觉核对。合成测试数据库和自有进程已停止，不动已有业务数据库或全局Docker配置。

运行时契约与backend文档YAML逐字节一致，OpenAPI3.0.3规范校验通过。新增路由响应断言涵盖身份ID字符串、必填字段、enum、nullable、时间偏移和响应状态。未把文档merge当作真实业务验收替代。

已检查git diff --check、私钥/真实token/共享初始口令扫描；依赖无H2、Boot3 MP或Knife4j Starter。新scope成员读取使用MyBatis，锁定与事务写入使用JdbcTemplate，均参数化。

## 审查状态

一次独立、只读的整分支审查范围 `cec5a63..391ac14`，发现5个Important、没有已确立的Critical。全部Important已在同一修复轮处理，没有派第二轮审查：

1. **撤权与repeatable-read快照竞争**：复现先读取target建立快照、另一事务禁用leader，旧实现继续写入；改为在锁定用户之后用当前FOR UPDATE读取角色/状态/安全版本、目标和scope，回归拒绝且target不变。
2. **退出与refresh竞争**：测试专用子类在真实authenticateAccess返回与真实logout锁定之间安排另一个真实SessionService刷新。旧实现报告退出成功但后继token仍活跃；现在锁内重查session的revoked/rotated/expiry，旧access已轮换时返回401，不虚报退出成功。
3. **维护配置来源不一致**：`-Dapp.migration.mode=migrate`曾打开Web并长驻；改用Spring环境准备事件解析统一有效配置，在createContext前设置nonWeb；同时迁移/初始化模式直接失败。系统属性、命令行和配置文件用正式JAR回归，迁移模式不创建管理员。
4. **context-path绕过**：`/space/api/v1/directions`曾匿名200；改用context-relative路径，回归401。未迁移context-path下仍503，未知路径404。
5. **授权历史缺失**：旧LEADER_UPDATED只有summary，无法复原scope变化；现在同事务记录old/newRole、old/newStatus、old/newScopes到受限detail，API仅显示summary且不暴露detail。

另将学号更正的字符格式问题按真实影响从Minor升为Important：旧实现可接受带空格账号并产生不符合契约响应；负面HTTP/契约校验失败后补与创建相同的pattern，非法更正400且原学号不变。

所有修复由执行者补RED→GREEN回归及最终完整套件证明，不以审查者静态意见代替执行验证。最终串行clean verify在poisoned profile/Hikari环境下通过：**34单元+47集成=81测试，失败/错误/跳过均0，BUILD SUCCESS**。维护模式、context-path、退出刷新竞争、事务撤权、授权审计和非法学号回归均执行。

**暂缓Minor：** 操作reason允许1–4字符但契约minLength为5；Retry-After仍固定60秒，非默认自定义窗口未反映剩余等待秒数。前者不提供越权能力但待DTO细化，后者在默认窗口正确、可能影响非默认窗口的客户端退避。两项均须对下一阶段明确交接，不宣称完全契约无差异。

审查不判断的事项逐项裁定：报告/AI/积分/月评/remove/restore不在本轮范围；真实部署TLS/凭据/数据库授权未提供、仍需上线验收；执行者已经复跑Knife4j测试探针，但不等于有前端业务UI；复制YAML的结构/示例遵循main已校验版本，本轮额外校验实际响应；schema drift/失败迁移细节不作“已覆盖所有情况”保证；审查后的README/fixture修正由执行者复核而非第二人复审。

## 执行取舍与代价

1. 保留已建立的干净backend工作目录，不新建checkout：变化直接位于当前工作区，但没有main代码改动。
2. 修复计划中误留的字面`+`前缀：仅Markdown解析变化，避免task1误吞task2。
3. 健康/JAR SQL边界更新提前到迁移任务：旧“所有SQL禁止/库无表”已不匹配新增设计；仍严格禁止测试SQL、测试类和账号种子。
4. Security Web依赖移至显式安全链任务，crypto先加入：避免未实现认证时触发默认密码/登录页，最终框架版本不变。
5. 纠正*IT用Failsafe和root Maven -f参数：只修验证命令，不以未运行测试充数。
6. 会话/幂等明确事务使用JdbcTemplate而非泛化BaseMapper，成员/方向读取分页使用MyBatis：需维护手写参数化SQL，真实库回归验证覆盖。
7. Runtime OpenAPI只覆盖已注册path/method，但复用批准设计的schema/operation metadata：资源契约副本需维护，已有等值和真实HTTPschema断言。
8. 早期有Maven验证重叠，最终全部严格串行：不依据重叠结果交付；代价是串行验收更耗时。
9. formatter临时JAR格式化新增Java提升可读性，未加入产品依赖；代价是format-only diff变大。
10. 维护模式统一使用Spring已解析环境决策，拒绝两模式共存；代价是入口新增启动阶段监听器，须维护配置来源回归。
11. 学号更正字符格式问题升级Important并修复：防止非法身份与前端契约不一致；代价是旧非法值不能通过更正接口写入，不触碰真实数据。
12. 不判断的下游功能保持未实现；代价是后续仍需新的业务阶段和政策确认。
13. 不判断的真实部署条件保留上线限制；代价是本轮通过不代表已完成公网安全部署。
14. 浏览器证据限定测试探针，实际业务HTTP另有集成测试；代价是没有前端业务UI验收。
15. YAML详细示例使用已有main校验结果并复核结构/响应；代价是本轮未重新逐字人工审查所有复制示例。
16. schema drift/失败迁移并非穷尽验收，保留风险；代价是上线前还需故障注入与迁移运维测试。
17. 审查后文档/fixture执行者自检，没有重复review；代价是这些变化没有第二人重新检查。

## 部署限制与下一步

启动方法见backend/README。普通Web启动未配置ADMIN_PASSWORD时正常，不自动开户。创建/重置学生需私密MEMBER_INITIAL_PASSWORD；共享口令领用核验仍需业务流程，不能认为强制改密可防止抢先登录。

限流是单进程有界固定窗口，缺省60秒10次；TLS由部署入口提供，跨域默认不开放，无Cookie方案。多实例全局限流、运行告警与备份恢复还需部署设计。MySQL8.0生命周期风险仍按已批准基础工程报告提示。

本轮仅本地backend提交，未推送/合并main，用户确认发布后才按指定分支推送。用户已取消关机，没有待关机计划。
