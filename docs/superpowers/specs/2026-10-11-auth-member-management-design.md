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

使用 Spring Security 提供的 `PasswordEncoder`，数据库只保存带随机盐的密码哈希。实现不得自行拼接密码哈希或记录原始密码；默认采用 BCrypt cost 12，并把 encoder 作为 Bean 便于未来迁移到 Argon2/PBKDF2。新密码长度 12–64 个字符，不静默 trim，不得等于当前密码或共享初始密码。

登录失败返回统一 `INVALID_CREDENTIALS`，不区分账号不存在、密码错误、已禁用或移出，避免账号枚举。登录、刷新和改密响应设置 `Cache-Control: no-store`；密码、access token、refresh token 不进入 URL、日志、异常详情或审计 summary。

### 3.3 受限首次登录

`must_change_password` 保存在账号上。使用初始密码登录时：

- 返回 HTTP 200、`code=OK`、短时 access token、`refreshToken=null`、`mustChangePassword=true`。
- 该 token 只允许 `GET /auth/me`、`PUT /auth/password`、`POST /auth/logout`。
- 访问其他受保护接口返回 HTTP 403、`PASSWORD_CHANGE_REQUIRED`。
- 改密成功后撤销全部旧会话，不返回新 token，客户端必须重新登录。

共享初始密码具有被抢先登录风险；本阶段不声称“强制改密”能完成身份核验。管理员初始化和账号发放必须使用部署私密配置和学院确认的交付流程。

## 4. 数据模型与迁移

不使用自动 DDL，不在应用启动时按实体建表。使用 Flyway 版本化 SQL 迁移；正式部署和测试都必须执行迁移，迁移失败则应用不可认为可用。第一版迁移建立以下逻辑表，实际字段命名以 SQL 与 Java 映射为准：

### 4.1 `direction`

- `id`、稳定 `code`、展示 `name`、`enabled`、创建/更新时间。
- 唯一约束：`code`、`name`。
- 迁移插入七个预置方向，使用固定业务 code；不插入负责人、学生或管理员。
- 方向目录按预置顺序返回；本阶段不开放普通 API 新增/删除方向。

### 4.2 `app_user`

- `id`、唯一 `account`、`password_hash`、`role`、`must_change_password`、`status`、`display_name`、`last_login_at`、时间字段、`version`。
- `account` 在全局账号命名空间唯一；学号账号由成员表关联但不重复存储为另一登录账号。
- `COLLEGE_ADMIN` 的 `member_id` 为空；`STUDENT` 与 `DIRECTION_LEADER` 必须关联一个成员。
- 管理员账号不由公开 API 创建。初始化采用受控的一次性维护命令/部署步骤，从私密环境变量读取账号、密码和显示名，成功后清除初始密码配置并将 `must_change_password` 按初始化策略设置；重复运行不得创建第二个管理员。

### 4.3 `member`

- `id`、唯一 `student_no`、姓名、专业、班级、年级、联系方式、成员状态、加入时间、在籍周期起止、移出原因、时间字段、`version`。
- `student_no` 是学生/负责人登录账号来源；普通 PATCH 永不写入该字段。
- `membership_status` 至少有 `ACTIVE`、`REMOVED`；`account_status` 与在籍状态分离。
- `total_points` 不作为本阶段可编辑字段；后续积分阶段以流水计算，不允许这里直接改分。

### 4.4 `leader_direction_scope`

- `user_id`、`direction_id`、创建/撤销时间、操作者。
- 唯一约束：有效状态下的 `user_id + direction_id`。
- 方向负责人角色由 `app_user.role` 表示，实际管理范围由此表表示；撤销任命清理有效 scope，但保留审计历史。
- 任命必须复用现有成员身份，不能用同一学号创建第二个用户。

### 4.5 `auth_session`

- `id`、`user_id`、`access_token_hash`、`refresh_token_hash`、refresh 家族标识、状态、创建/过期/最后使用/撤销时间、撤销原因、客户端摘要字段。
- access/refresh 摘要唯一；数据库不保存原始 token。
- 初始改密受限会话 `refresh_token_hash` 为空，access 过期时间短；正常会话同时有两种摘要。
- refresh 轮换在事务中完成：锁定旧会话，校验未使用且未撤销，标记旧会话已轮换，插入新会话；重放时撤销同家族活跃会话。

### 4.6 `audit_log`

- 操作者、动作、资源类型/ID、原因、结果、时间、脱敏摘要。
- 审计摘要不保存密码、token、完整联系方式、报告正文或模型原始输出。
- 强制记录：添加成员、修改成员、任命/撤销负责人、方向权限变更、密码重置、学号更正、账号禁用/移出、登录安全事件及 refresh 重放。
- 审计写入失败不得让密码/token 出现在异常信息中；关键管理操作与审计写入使用同一事务边界。

## 5. HTTP 与权限架构

### 5.1 Spring Security 边界

- 引入 Spring Security Web/Config，使用一个明确的 `SecurityFilterChain`。
- `/api/v1/auth/login`、`/api/v1/auth/refresh` 和健康检查公开；`/auth/me`、改密和退出要求有效 Bearer token，其中受限 token按允许列表处理。
- 其他 `/api/v1/**` 默认需要认证；未知路径仍返回 404，禁止通过认证入口自动创建账号。
- 自定义 Bearer 过滤器只负责解析 token、查会话和建立 Authentication；控制器/服务负责对象级方向范围校验。不能仅用 URL 角色注解替代成员范围校验。
- 未认证返回 401 `UNAUTHORIZED`，已认证但角色不足返回 403 `FORBIDDEN`，受限改密返回 403 `PASSWORD_CHANGE_REQUIRED`；统一异常处理不得把它们改成 200。
- CORS、HTTPS、限流属于部署和安全配置；本阶段至少保留本地默认绑定，不开放通配符跨域，不把 token 放 Cookie。

### 5.2 API 服务划分

- `AuthController/AuthService`：登录、refresh、退出、me、改密。
- `DirectionController/DirectionService`：固定目录查询。
- `MemberController/MemberService`：管理员/负责人范围内列表、添加、详情、资料 PATCH、学号更正、密码重置、移出/恢复（恢复仍 pending，先不实现）。
- `LeaderController/LeaderService`：负责人查询、任命、维护、撤销。
- `AuditService`：管理动作和安全事件脱敏写入。
- Mapper/Repository 只负责持久化；密码、会话轮换、版本校验和权限判断放在事务服务，不放进 Controller 或 SQL 拼接。

### 5.3 关键状态与事务

- 添加学生：校验操作者范围 → 校验学号/账号唯一 → 创建 member 与 app_user（初始积分为 0、mustChange=true）→ 写审计；重复请求通过唯一约束和 Idempotency-Key 返回 409，不创建第二个账号。
- 任命负责人：锁定成员/用户 → 必须是 ACTIVE 的 STUDENT → 切换角色并写 scope → 撤销会话 → 写审计；方向 scope 为空时拒绝任命。
- 修改负责人：只允许管理员，学号走更正接口；scope/状态变更立即撤销相关会话。
- 学号更正：锁定 member/user → 检查新学号和账号命名空间唯一 → 更新 account 与 student_no → 撤销全部会话 → 写原值/新值脱敏审计；内部 ID 和历史外键不变。
- 密码重置/改密：锁定用户 → 校验旧密码（改密）→ 写新哈希、mustChange 状态 → 撤销全部会话 → 写审计；不返回密码/token。
- 所有写接口使用契约中的 `version` 和 `Idempotency-Key`；版本冲突 409，重复幂等请求不能重复执行管理动作。

## 6. 管理员初始化与真实数据边界

- 本阶段不把用户提供的管理员账号密码写进 Git、迁移 SQL、测试资源或容器环境文件。
- 提供一次性、明确命名的管理员初始化命令/维护入口，仅允许在受控部署环境运行；输入来自外部私密配置，输出只报告成功/失败，不回显密码。
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

- Flyway 迁移只建立业务基础表和七方向，不建立真实管理员/学生，不执行破坏性 DDL。
- 登录/改密/refresh/退出全链路，检查数据库只存密码哈希和 token 摘要。
- 首次登录受限 token 只能访问 me/password/logout；业务接口 403。
- 旧 token 在改密、重置、学号更正、撤销负责人后立即失效。
- refresh 并发/重放只产生一个新会话；旧 refresh 重放撤销家族。
- 学号更正保留 member/user ID、历史引用和审计，旧学号不能登录。
- 学生不能注册；负责人不能跨方向添加或修改；管理员可以全方向管理和修改负责人。
- 外部 Hikari 专用属性不能劫持 Testcontainers 连接；继续使用已有 `OwnedDataSourceConfiguration` 隔离。

### 7.3 HTTP/打包验收

- OpenAPI 业务接口实现状态由 planned 逐步更新为 implemented；运行契约路径和响应与 main 文档一致。
- 正式 JAR 不包含测试探针、测试初始化器或真实账号/密码；默认健康和文档边界保持现有验收。
- 未提供真实管理员/负责人数据前，启动不会自动生成默认账号或虚构成员。

## 8. 非目标与风险

- 不在本阶段实现日报、周报、AI、积分、排行榜、月度评定或自动移出。
- 不引入 Redis、消息队列、统一身份认证或 Cookie 会话。
- 共享初始密码、管理员凭据交付和登录限流仍需上线前的运行策略；本设计不把密码写入仓库。
- MySQL 8.0 版本沿用已确认技术栈，但生命周期风险仍需上线前复评。

