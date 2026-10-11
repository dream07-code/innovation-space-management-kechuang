# 认证、方向权限与人员管理技术设计

| 项目 | 内容 |
| --- | --- |
| 日期 | 2026-10-11 |
| 文档版本 | v1.0 |
| 状态 | 待用户审阅 |
| 开发分支 | `backend` |
| 关联契约 | `main` 分支 `docs/api/openapi.yaml`，提交 `37797e4` |
| 业务时区 | `Asia/Shanghai` |

## 1. 目标与范围

本阶段实现学院科创空间的第一批业务能力：账号登录、首次强制改密、Bearer 会话、角色与方向权限、七个方向目录、学生成员管理、方向负责人任命与维护、学号更正、管理员密码重置及必要审计。

本阶段不实现日报、周报、AI 评分、积分、排行榜、月度评定、两周未交日报自动移出，也不擅自确定尚未确认的月评和积分政策。

API 路径、请求字段、响应状态和错误码以 main 分支 API 契约为准；后端实现后必须把运行中的 springdoc/Knife4j 契约与该文件同步。当前后端基础工程没有认证或业务表，不能把测试探针当作业务接口。

## 2. 已确认的业务规则

- 学生和方向负责人使用学号作为登录账号。
- 学生和方向负责人使用项目负责人指定的共享初始密码；实际密码只通过私密部署配置提供，不写入 Git、数据库明文、日志、OpenAPI 示例或响应。
- 学院管理员使用项目负责人指定的独立账号和密码；不提供公开注册，不从学生添加接口创建管理员。
- 学生只能由学院管理员或获授权的方向负责人添加；学生不能自行注册，也不能首次登录自动开户。
- 方向负责人只能管理自己获授权的方向；不能跨方向添加学生、修改其他方向或给自己/他人授予管理权限。
- 学院管理员可以管理方向负责人信息、负责方向和账号状态。
- 学号在普通资料修改接口中不可修改；错误学号必须经过管理员专用更正流程，保留内部身份 ID、历史报告和积分关联，记录审计并撤销旧会话。
- 首次登录或管理员重置密码后必须改密；改密前的受限会话不能访问普通业务接口。

## 3. 方案概览

### 3.1 认证方式

使用数据库会话配合随机、不透明 Bearer Token，不采用 JWT 作为本阶段的实现载体：

1. 登录成功后生成高熵随机 access token；正常登录同时生成 refresh token。
2. 只向客户端返回原始 token；数据库保存 token 的 SHA-256 摘要及会话元数据，不保存可直接使用的 token。
3. 每次请求通过摘要查找会话，检查撤销状态、过期时间、账号状态、成员资格和改密状态，再建立 Spring Security Authentication。
4. 修改密码、重置密码、学号更正、移出/禁用账号、负责人权限变化及退出都会立即撤销相关会话，不等待 token 自然过期。
5. refresh token 采用轮换：成功刷新后旧 refresh 摘要立即标记已使用/撤销并生成新会话凭据；重复使用旧 refresh token 时撤销该 token 家族并返回 401。

这满足契约中的即时撤销和 refresh 重放检测，代价是每个受保护请求需要一次数据库/缓存查找。本阶段不引入 Redis；MySQL 是唯一会话事实源，后续出现规模瓶颈时再单独设计缓存一致性。

### 3.2 密码

使用 Spring Security 提供的 `PasswordEncoder`，采用 PBKDF2-HMAC-SHA256、随机 16 字节盐、600000 次迭代、256 位派生结果，并以带算法标识的编码保存，为后续升级保留识别方式。实现不得自行拼接哈希算法或记录原始密码；encoder 作为 Bean 注入。新密码长度 12–64 个 Unicode 字符，不静默 trim，不得等于当前密码或共享初始密码。选择 PBKDF2 是为了不让 BCrypt 的输入字节限制与契约的字符长度、中文密码发生冲突；不额外引入原生密码库。

登录失败返回统一 `INVALID_CREDENTIALS`，不区分账号不存在、密码错误、已禁用或移出，避免账号枚举。登录、刷新和改密响应设置 `Cache-Control: no-store`；密码、access token、refresh token 不进入 URL、日志、异常详情或审计 summary。

### 3.3 受限首次登录

`must_change_password` 保存在账号上。使用初始密码登录时：

- 返回 HTTP 200、`code=OK`、短时 access token、`refreshToken=null`、`mustChangePassword=true`。
- 该 token 只允许 `GET /auth/me`、`PUT /auth/password`、`POST /auth/logout`。
- 访问其他受保护接口返回 HTTP 403、`PASSWORD_CHANGE_REQUIRED`。
- 改密成功后撤销全部旧会话，不返回新 token，客户端必须重新登录。

共享初始密码具有被抢先登录风险；本阶段不声称“强制改密”能完成身份核验。管理员初始化和账号发放必须使用部署私密配置和学院确认的交付流程。

## 4. 数据模型与迁移

不使用实体自动 DDL。使用 Flyway 版本化 SQL 迁移，新增 Boot 管理的 Flyway 及 MySQL 支持依赖，不改已选 Boot 版本。迁移是此阶段新增能力，不再沿用“正式包完全不能包含 SQL”的基础工程测试断言；只允许打包 `db/migration/V*.sql`，仍禁止测试建表 SQL、凭据和默认管理员数据。

默认应用启动不自动迁移。部署时先对明确指定的独立目标库执行专用迁移模式，再启动 Web 服务；迁移模式不启动 HTTP，不运行账号初始化，禁用 clean/baselineOnMigrate，不允许对未知非空库自动建立基线。正常服务核验迁移版本，不满足版本时 readiness 为 DOWN/503且所有需要业务表的入口统一503 SERVICE_UNAVAILABLE，不以“还没建表”当500或UP。集成测试对自有容器显式迁移；正式 JAR 测试同时验证未迁移/已迁移行为。此方式保持默认启动不擅自改现有库的边界，同时允许受控部署创建业务表。

第一版迁移建立以下逻辑表，所有时间字段使用 UTC `DATETIME(6)`，服务端输出带偏移的 ISO 时间，实际字段命名以 SQL 与 Java 映射为准：

### 4.1 `direction`

- `id`、稳定 `code`、展示 `name`、`enabled`、创建/更新时间。
- 唯一约束：`code`、`name`。
- 迁移插入七个预置方向，使用固定业务 code；不插入负责人、学生或管理员。
- 方向目录按预置顺序返回；本阶段不开放普通 API 新增/删除方向。

### 4.2 `app_user`

- `id`、唯一 `account`、`member_id`、`password_hash`、`role`、`must_change_password`、`status`、`display_name`、`last_login_at`、时间字段、`version`、`auth_revision`。
- `account` 在全局账号命名空间唯一；学号账号由成员表关联但不重复存储为另一登录账号。
- `member_id` 外键指向 member 且非空值唯一，创建时先插 member 再插 app_user。`COLLEGE_ADMIN` 的 `member_id` 为空；`STUDENT` 与 `DIRECTION_LEADER` 必须关联一个成员，member 不重复保存相互依赖的 user_id 外键，响应 userId 通过关联读取。
- `auth_revision` 是安全版本，用于撤销/刷新并发检查；登录时间更新不增加面向客户端的 Member.version，避免无关登录使资料 PATCH 冲突。
- 管理员账号不由公开 API 创建。初始化采用受控的一次性维护命令/部署步骤，从私密环境变量读取账号、密码和显示名，成功后清除初始密码配置并将 `must_change_password` 按初始化策略设置；重复运行不得创建第二个管理员。

### 4.3 `member`

- `id`、唯一 `student_no`、`direction_id`（外键）、姓名、专业、班级、年级、联系方式、成员状态、加入时间、在籍周期起止、移出原因、时间字段、`version`。
- member.version 是对外统一聚合版本。修改资料、角色、scope、账号状态、学号或密码重置均在锁定成员的事务中递增；返回 Member.version 不混用用户表 version。管理员本人密码修改没有 Member，不涉及该聚合版本。
- 本阶段一位成员只归属一个方向，任命负责人可以管理多个方向但不复制 member；多方向成员模型仍未开放。
- `student_no` 是学生/负责人登录账号来源；普通 PATCH 永不写入该字段。
- `membership_status` 至少有 `ACTIVE`、`REMOVED`；`account_status` 与在籍状态分离。
- `total_points` 不作为本阶段可编辑字段；后续积分阶段以流水计算，不允许这里直接改分。

### 4.4 `leader_direction_scope`

- `user_id`、`direction_id`、创建时间、操作者，联合主键为 `user_id + direction_id`，两者均有外键。
- 这里只存当前授权。撤销删除当前 scope，完整任命/撤销历史写审计；避免使用 MySQL 不支持的局部唯一索引表达“有效状态唯一”。
- 方向负责人角色由 `app_user.role` 表示，实际管理范围由此表表示；撤销任命清理有效 scope，但保留审计历史。
- 任命必须复用现有成员身份，不能用同一学号创建第二个用户。

### 4.5 `auth_session`

- `id`、`user_id`、`access_token_hash`、`refresh_token_hash`、refresh 家族标识、会话类型（NORMAL/PASSWORD_CHANGE）、创建时 auth_revision、状态、分别的 access/refresh 过期时间、创建/撤销时间、撤销原因。
- access/refresh 摘要唯一；数据库不保存原始 token。
- 初始改密受限会话 `refresh_token_hash` 为空，access 过期时间短；正常会话同时有两种摘要。
- access 与 refresh 使用独立 SecureRandom 随机 32 字节值，URL-safe Base64 编码；查询前严格校验长度/字符，不把超长攻击输入写数据库。只保存32字节SHA-256摘要。
- 普通access建议900秒；refresh家族绝对有效期604800秒，不因轮换无限延长；受限access300秒且无refresh。响应expiresIn是实际剩余秒数。refresh成功同时撤销旧access，不保留两个并行有效access。
- refresh 轮换在事务中完成：先锁用户，再锁旧会话，校验用户最新 auth_revision/状态和会话未使用，标记旧会话已轮换，插入同家族新会话；重放时撤销同家族活跃会话。
- **重放撤销必须提交后才返回401**：不能在撤销事务内抛出会导致rollback的业务异常。服务返回安全结果，Controller/Security边界在事务提交后转换HTTP错误。旧refresh摘要至少保留到家族绝对到期后，保证重放识别。
- 所有安全写入一致使用用户→成员（需要时）→会话的锁顺序；负责人操作同时重新验证操作者角色/scope，避免撤权与写入竞争。目标和操作者用户锁按ID升序获得，避免两用户操作死锁。

### 4.6 `api_idempotency`

- 唯一键为操作者、HTTP方法、规范化路径、客户端UUID key；保存规范化请求摘要、业务结果资源ID、必要安全响应快照、创建/到期时间。
- 原子取得幂等记录和业务事务，首次成功保留24小时。相同key不同语义请求409；同key并发相同请求只执行一次。权限/当前账号状态先于回放；回放成功结果先于目标资源version检查。
- 只为本阶段POST管理写入保存响应，响应不含密码/token。登录、refresh、logout不使用该表；普通PATCH/PUT依赖version。失败事务不保留“永久处理中”记录。过期删除不替代账号唯一性和资格状态约束。

### 4.7 `audit_log`

- 操作者、动作、资源类型/ID、原因、结果、时间、脱敏摘要。
- 审计摘要不保存密码、token、完整联系方式、报告正文或模型原始输出。
- 强制记录：添加成员、修改成员、任命/撤销负责人、方向权限变更、密码重置、学号更正、账号禁用/移出、登录安全事件及 refresh 重放。学号更正的旧/新值单独保存在权限受控的detail字段中，公开summary脱敏，普通学生和负责人不可查该detail。
- 审计写入失败不得让密码/token 出现在异常信息中；关键管理操作与审计写入使用同一事务边界。

## 5. HTTP 与权限架构

### 5.1 Spring Security 边界

- 引入 Spring Security Web/Config，使用一个明确的 `SecurityFilterChain`。
- `/api/v1/auth/login`、`/api/v1/auth/refresh` 和健康检查公开；`/auth/me`、改密和退出要求有效 Bearer token，其中受限 token按允许列表处理。
- 其他 `/api/v1/**` 默认需要认证；未知路径仍返回 404，禁止通过认证入口自动创建账号。
- 自定义 Bearer 过滤器只负责解析 token、查会话和建立 Authentication；控制器/服务负责对象级方向范围校验。不能仅用 URL 角色注解替代成员范围校验。
- 未认证访问已注册受保护接口返回401 UNAUTHORIZED；已认证但角色不足403 FORBIDDEN；受限改密403 PASSWORD_CHANGE_REQUIRED。未注册路由404、错误方法405、错误JSON内容类型415保持框架语义。实现须验证路由存在性，不能简单“所有/api/v1都401”覆盖未知路径。统一异常处理不能把401/403改200。
- 使用无状态Security上下文，不创建JSESSIONID，不启用formLogin/httpBasic/默认管理员。无Cookie认证时不使用Cookie CSRF方案；显式Origin白名单CORS默认空，不开放通配符。生产HTTPS由部署入口保证。
- 本阶段实现进程内有界速率限制：登录按IP+账号摘要，refresh按IP+token摘要，改密按用户；默认60秒10次、可配置。存储有容量和过期清理；不信任未配置代理的X-Forwarded-For。超限429 RATE_LIMITED+Retry-After且无原始账号/token日志。多实例时此限制非全局，生产多副本前另行升级方案。

### 5.2 API 服务划分

- `AuthController/AuthService`：登录、refresh、退出、me、改密。
- `DirectionController/DirectionService`：固定目录查询。
- `MemberController/MemberService`：管理员/负责人范围内列表、添加、详情、资料 PATCH、学号更正、密码重置。本阶段只完成这些已确认的人员与账号操作；移出/恢复需涉及未确认的资格/积分政策，暂不注册remove/restore路由，不能误标implemented。负责人资料中的DISABLED可立即停用账号，并不等于成员移出。
- `LeaderController/LeaderService`：负责人查询、任命、维护、撤销。
- `AuditService`：管理动作和安全事件脱敏写入；本阶段增加管理员只读 `GET /audits`，返回契约中的脱敏summary，不开放audit detail。
- Mapper/Repository 只负责持久化；密码、会话轮换、版本校验和权限判断放在事务服务，不放进 Controller 或 SQL 拼接。

### 5.3 关键状态与事务

- 添加学生：校验操作者范围 → 校验学号/账号唯一 → 创建 member 与 app_user（初始积分为 0、mustChange=true）→ 写审计；重复请求通过唯一约束和 Idempotency-Key 返回 409，不创建第二个账号。
- 任命负责人：锁定成员/用户 → 必须是 ACTIVE 的 STUDENT → 切换角色并写 scope → 撤销会话 → 写审计；方向 scope 为空时拒绝任命。
- 修改负责人：只允许管理员，学号走更正接口；scope/状态变更立即撤销相关会话。
- 学号更正：锁定 member/user → 检查新学号和账号命名空间唯一 → 更新 account 与 student_no → 撤销全部会话 → 写原值/新值脱敏审计；内部 ID 和历史外键不变。
- 密码重置/改密：锁定用户 → 校验旧密码（改密）→ 写新哈希、mustChange 状态 → 撤销全部会话 → 写审计；不返回密码/token。
- version由请求DTO带入，具体必填项逐接口对照契约：POST业务管理写入要求Idempotency-Key；PATCH/PUT不要求该header；创建成员没有version、任命/密码重置等目标变更有version，普通资料更新不得强行要求name。版本冲突409，重复幂等请求不重复执行管理动作。
- PATCH省略字段不变、nullable字段null清空，必须能区别未出现/null；不能直接用只支持null表示的普通record实现部分更新。请求未知字段、studentNo/role/password/points等禁止字段400，不静默丢弃以致客户端误以为修改成功。响应DTO显式映射，所有ID字符串，密码hash不进JSON。

### 5.4 现阶段未实现下游功能的隔离

- 成员响应 `totalPoints=0`，表示本阶段尚无积分事件，不允许客户端写入；不能建立临时积分表伪装后续实现。
- 在报告/积分/月评尚未实现时，directionId修改只更新现有成员归属和审计；未来引入快照后必须补冻结保护，不能承诺现有代码已做不存在的月评判断。
- `/api/v1/system/rules`、报告、积分、排名、月评、任务等路由暂不注册；API状态仍为planned/pending。文档同步仅改变真实已验收接口的状态。
- 管理端列表先在SQL中应用角色/方向过滤，再count/page；负责人查自己或其他负责人详细资料走auth/me或被拒绝，不能靠读全列表再内存过滤。

## 6. 管理员初始化与真实数据边界

- 本阶段不把用户提供的管理员账号密码写进 Git、迁移 SQL、测试资源或容器环境文件。
- 提供非HTTP的一次性初始化模式，使用Spring Boot应用的显式维护启动配置，不额外加入第二个JAR Start-Class。该模式独立于迁移，要求库已迁移；输入来自外部私密配置ADMIN_ACCOUNT/ADMIN_PASSWORD/ADMIN_DISPLAY_NAME，输出只报告成功/失败，不回显账号、密码或哈希。
- 默认Web启动没有ADMIN_PASSWORD要求、不会创建账号；初始化缺值/空值拒绝。存在管理员时重复调用只报告已初始化，不更新已有密码或新增另一个管理员。并发初始化锁数据库固定bootstrap记录后检查，保证不会生成两个初始管理员；不提供无认证HTTP初始化接口。全部测试只使用合成值。
- 初始化前必须确认目标数据库身份；禁止对已有未知数据库自动插入管理员或方向负责人。
- 负责人名单由用户后续提供后再录入；录入流程复用管理员管理接口或受控导入任务，必须逐条检查学号唯一、方向授权和审计。
- 集成测试只使用 Testcontainers MySQL 和虚构账号；不读取宿主机真实管理员环境变量作为测试数据。

## 7. 测试策略与验收

### 7.1 单元测试

- 密码策略、初始口令标记、token 摘要、过期/撤销判断、refresh 轮换和重放撤销。
- 统一错误状态：401/403/PASSWORD_CHANGE_REQUIRED/409/429，不回显密码/token。
- 负责人方向 scope 判断：授权方向允许，跨方向拒绝；负责人不能写 role、scope 或跨方向。
- 普通 PATCH 不可写学号；更正接口必须管理员、唯一、版本匹配和审计。

### 7.2 MySQL 集成测试

- Flyway迁移只建立本阶段基础表、七方向和无个人数据的bootstrap互斥记录，不建立真实管理员/学生，不执行破坏性DDL。默认启动不迁移，迁移失败/未迁移健康正确、业务503；明确迁移只允许独立库。
- 登录/改密/refresh/退出全链路，检查数据库只存密码哈希和 token 摘要。
- 首次登录受限 token 只能访问 me/password/logout；业务接口 403。
- 旧token在改密、重置、学号更正、停用、撤销负责人后立即失效；移出后撤销作为未来资格模块的验收，本阶段不虚构remove实现。
- refresh 并发/重放只产生一个新会话；旧 refresh 重放撤销家族。
- 学号更正保留 member/user ID、历史引用和审计，旧学号不能登录。
- 学生不能注册；负责人不能跨方向添加或修改；管理员可以全方向管理和修改负责人。
- 外部 Hikari 专用属性不能劫持 Testcontainers 连接；继续使用已有 `OwnedDataSourceConfiguration` 隔离。

### 7.3 HTTP/打包验收

- OpenAPI 业务接口实现状态由 planned 逐步更新为 implemented；运行契约路径和响应与 main 文档一致。
- 正式 JAR 不包含测试探针、测试初始化器或真实账号/密码；默认健康和文档边界保持现有验收。
- 未提供真实管理员/负责人数据前，启动不会自动生成默认账号或虚构成员。正式JarBoundaryIT允许迁移SQL的精确白名单，不放开所有SQL或tests资源；正式JAR各profile测试升级为检查本阶段实际路径，不再断言paths永远空或业务库永远无表。

### 7.4 文档及执行边界

- 实施计划写出基础工程旧断言的逐项迁移：Health无表→迁移/默认不改库；JAR禁所有SQL→精确迁移白名单；dev文档空paths→实际本阶段路径；公开测试探针→test-only配置下受控鉴权。
- 不跳过原有Hikari劫持/默认profile/健康安全回归。新安全链需要区分原测试fixture与生产，不为了测试公开全部业务路由。
- 不自动推送main；本阶段代码和设计保留backend，只有用户确认发布时才按指定分支推送。设计审阅通过后再写详细TDD实施计划，本设计本身不是实施授权。

## 8. 非目标与风险

- 不在本阶段实现日报、周报、AI、积分、排行榜、月度评定或自动移出。
- 不引入 Redis、消息队列、统一身份认证或 Cookie 会话。
- 共享初始密码、管理员凭据交付和登录限流仍需上线前的运行策略；本设计不把密码写入仓库。
- MySQL 8.0 版本沿用已确认技术栈，但生命周期风险仍需上线前复评。
