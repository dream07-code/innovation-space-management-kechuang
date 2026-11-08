# AI 编程实训助手

**AI 编程实训助手 —— 面向编程教学的 AI 教育与学习平台。**

学生在线写代码交题，判题机真实编译执行；客观题自动判分，主观题由大模型按评分标准给出档位与依据，教师复核后可一键发布成绩。学习过程用日报、周报、积分与排行榜留痕，教师端能看到每个方向、每个人的进度。

## 项目来源

本项目源自作者 2026 年 10 月起为学校科创空间开发并持续使用的创新空间管理系统，参赛版本复制到本仓库后，按 AI 教育方向做了改版，原仓库继续作为科创空间生产系统运行。

## 参赛信息

| 项目 | 内容 |
| --- | --- |
| 赛事 | 2026 第九届传智杯 AI Web 网页开发挑战赛 |
| 主选方向 | 5 号 AI 教育与学习平台 |
| 兼及方向 | 1 号 AI Agent、7 号 AI 代码开发辅助 |

## 要解决的问题

编程课的教学闭环里，三个环节最容易断：

1. **练习没有反馈。** 学生写完代码只能等老师看，一次作业的反馈周期以天计，练不动。
2. **判分口径不统一。** 不同助教对同一份填空、同一段说明给分差异大，学生不服气，老师也说不清依据。
3. **学情看不见。** 谁在练、练到哪、卡在哪，散在聊天记录和作业压缩包里，期中才发现有人掉队。

本平台把这三点合到一条链上：**写代码 → 机器判题 → AI 给分并说明依据 → 教师复核 → 成绩发布 → 过程进积分与看板。**

## 核心能力

### 1. 真实判题，不是模拟

- 接入自建 **Judge0**，代码在隔离沙箱里真实编译执行，不是返回写死的答案。
- 支持 Java、Python、C++、JavaScript、Go、Rust 六种语言，适配器按语言 ID 提交。
- 函数式判题：学生只填函数体，服务端套 harness 生成完整程序，语法细节不干扰考核重点。
- 判题走服务端有界并发队列，单次提交按测试点逐个执行并汇总通过率、耗时、内存。
- 适配 cgroup v2 主机：Judge0 在缺少 cgroup v1 的环境下会返回内部错误，这里提供 rlimit 逐进程限制模式，已在真实环境用 Python 样例验证到 `Accepted`。

### 2. AI 评分：给分，也给依据

- 通过 **openai-compatible** 接口调用大模型，部署环境使用 Mimo 的 `mimo-v2.6-flash`；接口地址、模型、密钥全部由环境变量注入。
- 评分标准是显式代码（`ScoringRubric`，当前 v2）：日报、周报各有一套 1–3 分档位，只看内容的具体性、可检验收获与反思，**不按篇幅和文风加减分**。
- 异步评分 Worker：提交后不阻塞学生，评分任务带版本号，旧版本迟到的结果只标记 `STALE` 不覆盖；人工结果优先于 AI 结果。
- 报告正文按**不可信数据**处理，prompt 里明确要求忽略正文中的指令，防注入。
- 不配置密钥时不调用外部服务，功能自动关闭而不是伪造分数。

### 3. 阅卷：一次拿全题干

- 阅卷页的汇总接口 `GET /api/v1/assessments/{assessmentId}/quiz-review/summary` 直接下发 `questions` 题干清单与进度，不再是「先查题库再逐题回查」的 N 次串行请求——远程网络下这一项把首屏从分钟级压到一次往返。
- 客观题按标准答案自动判分，填空题进人工评分队列，左右分栏一个个过。
- 全部批完才允许发布；发布后学生端才看得到成绩，未参加的考核不显示 0 分。
- 成绩可折算为积分进入流水，但不影响日报周报的积分口径（OJ 结果不写 `point_ledger`）。

### 4. 学习过程与反馈

- 日报 / 周报：草稿、合并保存、提交、版本留存，支持笔记图片（腾讯云 COS）。
- 积分流水与排行榜：总榜、方向榜、个人排名；负责人不计入学生榜。
- 14 个完整自然日未交日报自动移出，管理员可恢复并开始新的观察期。

### 5. 管理与支撑（底座功能）

平台先是一套能长期跑在学校的系统，才有教学数据可用，因此包含完整的管理与权限能力：

- 七个方向的人员管理，学生账号只能由负责人或管理员创建，不开放自注册。
- 三级角色（学院管理员 / 方向负责人 / 学生）与方向级数据隔离，**权限在后端逐对象校验**，前端隐藏按钮不算安全边界。
- 会话安全：不透明 token 只存哈希、刷新轮换、改密即撤销会话、登录限流。
- 审计留痕、公告、角色化数据看板（管理员 / 负责人 / 学生三套聚合口径）。
- Knife4j + OpenAPI 契约，前后端以契约为准并行开发。

## 技术架构

| 层 | 选型 |
| --- | --- |
| 后端 | Java 21、Spring Boot 4.1.1、MyBatis-Plus 3.5.17、Flyway 受控迁移 |
| 数据库 | MySQL 8.0 |
| 前端 | Vue 3.5、Vite、TypeScript、Element Plus、Pinia |
| 判题 | 自建 Judge0 1.13.1（隔离容器 + PostgreSQL + Redis） |
| AI 评分 | openai-compatible 接口（部署使用 Mimo `mimo-v2.6-flash`） |
| 对象存储 | 腾讯云 COS（笔记图片） |
| 部署 | Docker Compose + Nginx 同源（SPA、`/api` 反代、健康检查） |

安全约束写在实现里，而不是文档里：**没有默认管理员、没有默认密码、没有提交进仓库的密钥**。数据库密码、Judge0 Token、AI Key、COS 凭据一律从环境变量注入，缺失时应用启动失败或功能关闭，不会降级成假数据。

## 本地运行

前置：JDK 21、Node.js 24、pnpm 11、MySQL 8.0；判题与笔记图片需要 Docker（Judge0）和 COS 凭据，纯前端联调可先不接。

### 1. 准备数据库

```bash
# 建一个独立开发库，示例端口 3307，避免占用本机 3306
docker run -d --name aispace-dev-mysql \
  -p 127.0.0.1:3307:3306 \
  -e MYSQL_ROOT_PASSWORD='<一次性开发密码>' \
  -e MYSQL_DATABASE=innovation_space \
  -e MYSQL_USER=innovation_app \
  -e MYSQL_PASSWORD='<开发库密码>' \
  mysql:8.0
```

### 2. 启动后端

```bash
cd backend
export DB_HOST=127.0.0.1 DB_PORT=3307 DB_NAME=innovation_space DB_USERNAME=innovation_app
read -rsp 'DB password: ' DB_PASSWORD; printf '\n'; export DB_PASSWORD
export SPRING_PROFILES_ACTIVE=dev

./mvnw clean package -DskipTests

# 受控迁移：只跑 Flyway 建表，不启动 HTTP
java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar --app.migration.mode=migrate

# 初始化管理员（非 HTTP 维护模式，缺变量直接失败）
ADMIN_ACCOUNT=admin ADMIN_PASSWORD='<强口令>' ADMIN_DISPLAY_NAME='管理员' \
  java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar --app.bootstrap.mode=admin

# 正常启动
java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar
```

健康检查 `http://127.0.0.1:8080/actuator/health`；`dev` 环境接口文档 `http://127.0.0.1:8080/doc.html`。

### 3. 启动前端

```bash
cd frontend
pnpm install
pnpm dev          # http://localhost:5173，/api 与 /actuator 自动代理到 127.0.0.1:8080
```

后端显式关闭了 CORS，浏览器不能跨域直连，开发环境必须走 Vite 代理。开发环境默认调用真实后端；`src/mocks` 里的虚构数据只有显式设置 `VITE_USE_MOCK=true` 才会启用，演示不用模拟数据。

### 4. 接真实判题与 AI 评分（可选）

```bash
# 判题：judge0=远程判题机；mock 仅供测试，不作为功能演示
export OJ_PROVIDER=judge0
export OJ_BASE_URL=http://127.0.0.1:2358
export OJ_API_TOKEN='<Judge0 Token>'
export OJ_RLIMIT_FALLBACK=true      # cgroup v2 主机必须打开

# AI 评分：openai-compatible 接口
export AI_SCORING_ENABLED=true
export AI_PROVIDER=openai-compatible
export AI_BASE_URL='<兼容端点，必须 HTTPS>'
export AI_API_KEY='<模型密钥>'
export AI_MODEL=mimo-v2.6-flash
```

Judge0 独立部署栈见 [`deploy/judge0/README.md`](deploy/judge0/README.md)；一键演示部署（含 Judge0 自动接线）见 [`deploy/README.md`](deploy/README.md)。

### 5. 跑测试

```bash
cd backend && ./mvnw test        # 单元测试，不需要 Docker
cd frontend && pnpm test         # 前端单测
```

## 目录结构

```text
backend/            Spring Boot 后端（judge0 适配、评分 Worker、阅卷、人员与积分）
frontend/           Vue 3 前端（OJ 考核、代码提交、阅卷、日报周报、看板）
deploy/             生产部署：Compose、Nginx、一键脚本、Judge0 独立栈
tools/oj-bank/      题库与转换工具
docs/               需求、API 契约、OJ 函数式判题说明、设计与验收记录
```

## 文档

- [需求文档](docs/requirements.md)
- [前后端共用 API 契约](docs/api/README.md)
- [OJ 函数式判题说明](docs/oj-function-mode.md)
- [后端说明：环境变量、迁移、迁移后验证](backend/README.md)
- [生产部署说明](deploy/README.md)
- [题库工具说明](tools/oj-bank/README.md)

## 数据与仓库安全

- 不提交真实学生名单、学号、联系方式、日报原文或其他个人信息。
- 不提交数据库备份、访问令牌、Judge0 Token、AI 密钥和 COS 凭据；`.env` 已被忽略。
- 测试使用虚构数据；部署环境密钥由环境变量或密钥管理服务提供。
- 暂未选择开源许可证。
