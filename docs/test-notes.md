# 端到端试跑记录（test-notes）

| 项目 | 内容 |
| --- | --- |
| 首次记录 | 2026-11-10（环境不满足，未执行） |
| 本次更新 | 2026-11-10 深夜，**本机已跑通全流程** |
| 记录人 | 徐墺锟（测试） |
| 结论 | 环境搭起来了，13 条操作验收 + 3 个重点确认项**全部通过**；AI 相关功能因未配置密钥**未验证**；另发现 3 个需要确认的问题 |

> 密码与密钥说明：本次所有口令（MySQL root、应用库、学生初始口令、管理员口令、学生改密后的口令）都由本机随机生成，写在仓库外的 `D:/develop/_local-runtime/local.env`（权限 600），**未写入本文档、未提交仓库、未出现在对话里**。AI 密钥全程未配置。

---

## 1. 环境（本次实测版本）

| 依赖 | 要求 | 实际 | 结果 |
| --- | --- | --- | --- |
| JDK | 21（`pom.xml` 要求） | Temurin **21.0.13+9**，装在 `D:/develop/_local-runtime/jdk-21.0.13+9` | ✅ `java -version` 与 `mvn -v` 的 runtime 都是 21.0.13 |
| Maven | 3.9+ | 3.9.16（仓库自带 wrapper） | ✅ |
| MySQL | 8.0 | **8.0.29**（ZIP 免安装版），`127.0.0.1:3306` LISTENING | ✅ |
| Node | 24 | 22.22.2 | ⚠️ 低两个大版本，构建与单测均正常 |
| pnpm | 11 | 12.18.0（corepack 提供） | ✅ |
| Docker | 判题可选 | 无 | ✅ 走 `OJ_PROVIDER=local`，不需要 Docker |

安装来源：JDK 21 与 MySQL 8 均从国内镜像下载 ZIP 解压（清华 Adoptium 镜像 / 华为 MySQL 镜像），**没有改动系统 PATH、没有注册 Windows 服务、没有装到仓库目录**。

## 2. 本地运行配置（全部在仓库外，仓库零改动）

- 配置文件：`D:/develop/_local-runtime/{local.env, env.sh, my.ini, maven-settings.xml}`
- 关键开关：`OJ_PROVIDER=local`（真实调用本机 `python3` / `javac`，不起 Judge0）、`AI_PROVIDER=disabled`、`SERVER_PORT=8081`、`SPRING_PROFILES_ACTIVE=dev`、`REPORT_POINTS_POLICY_STATUS=READY`
- 前端代理：启动时用环境变量 `VITE_DEV_PROXY_TARGET=http://127.0.0.1:8081` 覆盖默认的 8080，**没有改 `vite.config.ts`**
- `git status` 全程干净，`node_modules` 与 `target` 均被既有 `.gitignore` 忽略

### 踩到的两个环境坑（与项目无关）

1. **Maven 直连 central 反复失败**：`spring-boot-starter-security:4.1.1`、`spring-boot-webmvc:4.1.1`、`mysql-connector-j:9.7.0` 三个包稳定报 `Remote host terminated the handshake`，加重试参数无效。改用阿里云镜像（`-s maven-settings.xml`）后一次通过。**不是项目依赖问题。**
2. **Git Bash 的 PATH 不能塞 Windows 盘符路径**：`export PATH="$JAVA_HOME/bin:$PATH"` 会被 `:` 截断成 `D` + `/develop/...`，导致 `java` 仍是 17。必须写 POSIX 路径 `/d/develop/.../bin`。**给后来者省半小时。**

## 3. 迁移与账号

```
Flyway: Successfully applied 17 migrations to schema `innovation_space`, now at version v17
库内核对: MAX(version)=17，applied=17，表 34 张，direction 预置 7 条，app_user 初始 0 行
```

- 迁移前库里 0 张表；`--app.migration.mode=migrate` 只建表不启 HTTP，符合文档。
- 管理员用 `--app.bootstrap.mode=admin` 创建（输出 `Administrator initialization completed`，不回显口令）。
- 学生 `DEMO2026001` 由管理员通过 `POST /api/v1/members` 创建，方向 `AI_FULLSTACK`，初始口令取 `MEMBER_INITIAL_PASSWORD`。
- **仓库里确实没有种子用户**：`app_user` 在建管理员前是 0 行，演示数据全部现场产生。

## 4. 服务启动

| 服务 | 地址 | 证据 |
| --- | --- | --- |
| 后端 | `127.0.0.1:8081` | `GET /actuator/health` → `{"status":"UP"}`；启动耗时约 4s |
| 前端 | `127.0.0.1:5174` | `GET /login` → HTTP 200，标题「AI 编程实训助手」 |
| 代理链路 | 5174 → 8081 | `GET 5174/actuator/health` → `{"status":"UP"}` |
| 页面渲染 | Edge 无头浏览器 | 登录页 DOM 385KB，含「欢迎回来」「登录 AI 编程实训助手」「学号或管理员账号」「不提供自助注册」等真实文案 |

> 说明：本次验收以 **HTTP 接口 + 真实判题** 为主（前端调用的就是这些接口），页面层只做了登录页的真实浏览器渲染验证。涉及「2 秒自动保存」「限流提示文案」这类纯前端文案的项，标注为「接口已验证 + 文案取自源码」，没有逐字在浏览器里点。

---

## 5. 验收表逐项结果

上次写的验收表实际是 **13 条勾选项**（不是 14 条），加上 3 个「只能靠实跑确认」的重点项，共 16 项。逐项结果如下。

### 5.1 操作验收（13 项）

| # | 验收项 | 结果 | 实测证据 |
| --- | --- | --- | --- |
| 1 | 学生登录 → 强制改密 → 进入首页 | ✅ 通过 | 初始口令登录返回 `mustChangePassword=true`、`refreshToken=null`；受限令牌调业务接口 `HTTP 403 PASSWORD_CHANGE_REQUIRED`；`PUT /auth/password` 返回 `data=null`；改密后旧令牌 `401`；新口令登录 `mustChangePassword=false`、`role=STUDENT` |
| 2 | 考核详情 → 进入考试 → 试卷页三项齐全 | ✅ 通过 | `GET /assessments/1/paper` 返回 3 条：`#1 PROGRAM 斐波那契数列 60 分`、`#2 QUIZ 单选 20 分 4 选项`、`#3 QUIZ 填空 20 分` |
| 3 | 改答案后 2 秒自动暂存 | ✅ 接口通过 | `PUT /answer-sheet` 写入 `{"2":"C","3":"return max(items)"}` 后 `status=DRAFT`、`answers` 原样回读；2 秒防抖取自源码 `AUTOSAVE_DELAY_MS=2000`（未在浏览器里掐表） |
| 4 | 试运行抽 1 个正式测试点 + 第 11 次限流 | ✅ 通过 | 连续 12 次：**前 10 次 HTTP 200 `ACCEPTED`，第 11、12 次 HTTP 429 `RATE_LIMITED`**，与「每分钟 10 次」完全一致 |
| 5 | 提交代码 → 已判定 → 出现参考测试点 | ✅ 通过 | 提交后轮询 1 次即 `JUDGED`，`verdict=ACCEPTED`、`3/3`、`309ms`；`sampleCase={position:3, input:"[20]", actualOutput:"6765", expectedOutput:"6765"}`——只回 1 个测试点，其余隐藏 |
| 6 | 提交记录 → 批改报告 | ✅ 通过（AI 段按设计降级） | 报告含判题结果 `ACCEPTED 3/3`；`ai.available=false`，原因「AI 批改未启用：需要 AI_SCORING_ENABLED=true 与 AI_PROVIDER=openai-compatible。」；推荐下一题 `rule=NEXT_DIFFICULTY`、理由「本次通过，难度升到中等。当前题库没有匹配难度的其他题目。」、`problem=null`（不硬凑） |
| 7 | 报告页问助教 → 追问 2 轮 → 刷新仍在 | ✅ 通过（回答按设计降级） | 提交内追问返回 `200 + available=false + 原因`；同一会话再问 2 轮，`GET /conversations/{id}/messages` 返回 **2 条学生消息**，即「AI 不可用也不丢问题」 |
| 8 | 独立助教：新建 → 关联题目 → 提问 → 重命名 → 删除 | ✅ 通过 | 新建 `{problemId:1}` 返回 `id=2 problemId=1`；`PATCH` 改名成功返回「斐波那契答疑」；未知会话 `HTTP 404`（不区分「不存在」与「不是你的」）；删除后会话数 2→1 |
| 9 | 学习画像 4 个数字卡 + 薄弱点 + 推荐；新学生空状态 | ✅ 通过 | 有数据：`hasData=true`、尝试 1、通过 1、通过率 100%、`averageScore=60`、提交 1 次、薄弱点 `[]`、推荐 `[]`（题库里没有未通过的同难度题）。空状态由 `hasData=false` 分支控制（见 §6 问题 1） |
| 10 | 交卷 → 客观题锁定 → 成绩待公布 | ✅ 通过 | 交卷后 `status=SUBMITTED`、`totalScore=null`；`my-answer-sheet` 返回 `resultsPublished=false`、`items=null`（未发布不下发逐题明细）；学生看成绩 `totalScore=null`、`scoreRate=null` |
| 11 | 填空阅卷 → 打分 → 发布成绩 → 学生可见 | ✅ 通过 | 阅卷前 `pendingItems=1, canPublish=false`；打分 20 分并写评语后 `pendingItems=0, canPublish=true`；发布后学生 `member-scores` 显示 **完成 3/3、总分 100/100、得分率 100%**，答题卡逐题显示「我的答案 / 正确答案 / 对错 / 得分 / 评语」 |
| 12 | 人工批阅改分 → 人工分覆盖自动分 | ✅ 通过 | 自动分 60，人工评 55 后 `reviews` 返回 `55 分 ACTIVE`；批阅历史可查。（最终分由前端按 `activeReview ?? autoScore` 计算，接口不直接返回 `finalScore`） |
| 13 | 管理员看板 7d/30d、方向对比、最近动态 | ✅ 通过 | `range=30d`：在籍学生 1、今日日报 0、待判定 OJ 0、方向 7 条；`range=7d`：趋势 7 个点；响应体内**未出现** `studentNo` / `contact` / `codeText` / `phone` |

### 5.2 三个重点确认项（上次只能靠猜的）

| 重点项 | 结论 | 证据 |
| --- | --- | --- |
| 考核截止后是否自动代交草稿 | **会**，且同时把考核置为已关闭 | 新建考核 2，`endAt=now+50s`，学生只存草稿不交卷。轮询结果：`+50s` 仍是 `PUBLISHED / DRAFT`，**`+60s` 变为 `CLOSED / SUBMITTED`**（调度器 30 秒一轮）。代交后 `totalScore=null`，因为成绩未发布 |
| 考核进行中是否限制正式提交次数 | **不限制** | 考核 3（已发布）连续提交 3 次，全部 `HTTP 201`，生成 3 条独立提交（id=2/3/4）。只有「试运行」限流，正式提交没有 |
| 题库练习是否计入学习画像 | **计入活动指标，均分不算**（原结论为旧版行为，已由 `bc18c38` 改为计入） | 开发 agent 实测（提交 `bc18c38`）：练习 2 次后画像做题数 1→2、提交数 2→4、通过率 100%→50%，「已发布成绩均分」100 不变。本次本地实跑（旧版本，`submissionCount=1`、`attempted=1` 未变）记录的是**改动前**的行为，仅作历史对照 |

### 5.3 顺带验证的权限与安全边界

| 检查 | 结果 |
| --- | --- |
| 学生越权建题目（合法载荷） | ✅ `HTTP 403 FORBIDDEN` |
| 学生提交记录范围 | ✅ 只返回本人 1 条，无他人数据 |
| 学生报告页的客观题题面 | ✅ `paperQuestions=[]`（仅管理角色可见） |
| 他人/不存在会话 id | ✅ 一律 `404`，无法用 id 探测同学话题 |
| 已关闭考核继续提交 | ⚠️ `HTTP 404 NOT_FOUND`（见问题 2） |

### 5.4 自动化测试实跑

| 套件 | 命令 | 结果 |
| --- | --- | --- |
| 前端单测 | `pnpm test`（vitest） | **55 个文件 / 259 个用例全部通过**，22.9s |
| 后端单测 | `./mvnw test` | **222 个用例通过，0 失败，0 错误，4 跳过**，BUILD SUCCESS，22.6s |

> 后端 `*IT` 集成测试需要 Docker（Testcontainers），本机没有 Docker，本次未跑；`mvn test` 只跑 surefire 的单元测试。

---

## 6. 发现的问题（按严重程度）

### 问题 1：画像「已发布成绩均分」与成绩页口径不一致（建议修）

- 现象：同一名学生，成绩页显示 **总分 100/100**，画像页「已发布成绩均分」显示 **60 分**。
- 原因：`JdbcTutorRepository.averagePublishedScore()` 取的是 `ROUND(AVG(assessment_submission.final_score))`，即**按提交**（这道程序题 60 分）平均；成绩页的「总分」是**按考核**把各条目分值加总（60 + 20 + 20 = 100）。SQL 注释写「与成绩页同一口径」，实际不是。
- 影响：学生会以为「我考了 100，画像说我均分 60」。数字都真实，但标签「已发布成绩均分」容易被读成「考核总分均分」。
- 建议：要么改 SQL 按考核总分聚合，要么把标签改成「单题得分均分」并在旁边注明口径。**属于口径问题，不是数据造假。**

### 问题 2：考核关闭后提交返回 404，提示不友好（建议改）

- 现象：对已关闭考核调 `POST /assessments/{id}/submissions`，返回 `HTTP 404 {"code":"NOT_FOUND","message":"Resource not found"}`。
- 影响：学生看到的是「资源不存在」，而不是「本场考核已关闭，不再接收提交」。前端只能按通用错误提示，学生不知道发生了什么。
- 建议：返回 409 + `ASSESSMENT_CLOSED` 之类的业务码，前端给明确文案。当前学生从「试卷页交卷按钮」走不到这里（按钮已禁用），但历史页面/刷新后重试会撞上。

### 问题 3：接口对请求体字段集合校验极严，联调容易卡（文档缺失）

- 现象：请求体多一个字段就整单 `INVALID_REQUEST`，且不告诉你是哪个字段。本次踩到两次：
  - `POST /assessment-problems/{id}/practice-run` 必须**恰好** `{language, code}`，多带 `problemId` 直接被拒；
  - `POST /assessment-submissions/{id}/reviews` 必须**恰好** `{submissionVersion, reviewVersion, score, comment}`，且 `reviewVersion` 必须 ≥ 1（首次评阅传 0 会被拒）。
- 影响：这是有意的严格校验（防字段注入），但对第一次接的人不友好，容易误判成「功能坏了」。
- 建议：在 `docs/api/README.md` 里给每个写接口列出**精确字段集合**，或在错误信息里回显「unsupported fields [x]」。顺带一提，后端 `GlobalExceptionHandler` 在别的场景已经会回显 `unsupported fields [directionId]`，这条链路没回显。

### 其他观察（不算缺陷）

- 本机 MySQL 的 X Plugin 监听在 `0.0.0.0:33060`（8.0 默认），本地验收无所谓，但如果是共享机器建议关掉。
- 前端构建产物与单测都正常，未发现类型错误。

---

## 7. 未验证的部分（如实说明）

| 功能 | 状态 | 原因 |
| --- | --- | --- |
| AI 批改报告的正文（错因解析 / AI 点评、改进建议） | **未验证** | 未配置 `AI_API_KEY`（按约定不碰用户的 key）。已验证的是「未启用时如实降级 + 明确原因 + 不生成占位内容」 |
| AI 助教的回答内容（苏格拉底式引导、示例代码语言） | **未验证** | 同上。已验证会话落库、重命名、删除、越权 404 |
| 报告「命中缓存」标记 | **未验证** | 缓存只对成功的 AI 结论生效；AI 未启用时 `cached=false`，无法构造命中场景。源码中 `CACHE_TTL_MS = 30 * 60 * 1000` |
| 笔记图片上传（COS） | **未验证** | 未配置 COS 凭据；核心流程不需要，启动也未报错 |
| 日报 / 周报提交与积分、排行榜、自动移出（14 天） | **未验证** | 与本次 OJ 主链路无关，未构造数据。已确认 `direction` 预置 7 条、`app_user` 无种子数据 |
| 后端 `*IT` 集成测试 | **未执行** | 需要 Docker + Testcontainers |
| 手机端窄屏布局（`max-width: 767px` 标签切换） | **未验证** | 未在移动视口下点 |

---

## 8. 复现步骤（下次换机器照这个来）

```bash
# 0) 仓库
git clone https://github.com/dream07-code/innovation-space-management-kechuang.git
cd innovation-space-management-kechuang

# 1) 依赖（本次装在仓库外，避免污染）
#    JDK 21: https://mirrors.tuna.tsinghua.edu.cn/Adoptium/21/jdk/x64/windows/
#    MySQL 8: https://mirrors.huaweicloud.com/mysql/Downloads/MySQL-8.0/
#    解压后用 my.ini 指定 basedir/datadir/port=3306/bind-address=127.0.0.1，
#    先 mysqld --initialize-insecure，再 mysqld --console 启动

# 2) 环境变量（仓库外 env.sh，密码现场生成，不写进仓库）
export JAVA_HOME=<jdk21>; export PATH=<jdk21>/bin:$PATH
export DB_HOST=127.0.0.1 DB_PORT=3306 DB_NAME=innovation_space DB_USERNAME=innovation_app
export DB_PASSWORD=<现场生成> MEMBER_INITIAL_PASSWORD=<现场生成>
export SERVER_PORT=8081 SPRING_PROFILES_ACTIVE=dev
export OJ_PROVIDER=local AI_PROVIDER=disabled AI_SCORING_ENABLED=false
export REPORT_POINTS_POLICY_STATUS=READY

# 3) 构建 + 迁移 + 建管理员
cd backend && ./mvnw -B -s <mirror-settings.xml> package -DskipTests
java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar --app.migration.mode=migrate
ADMIN_ACCOUNT=admin ADMIN_PASSWORD=<现场生成> ADMIN_DISPLAY_NAME=学院管理员 \
  java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar --app.bootstrap.mode=admin
java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar &

# 4) 前端（代理指向 8081，不改配置）
cd ../frontend && corepack pnpm install
VITE_DEV_PROXY_TARGET=http://127.0.0.1:8081 corepack pnpm dev --port 5174 --host 127.0.0.1
```

演示数据（本次用脚本调接口生成，全部走正式接口，没有直接插库）：

1. 管理员登录 → `POST /members` 建学生 `DEMO2026001`（方向 1）
2. `POST /assessment-problems` 建函数题 `fib(int)->int`，3 个正式测试点 + 2 个练习测试点
3. `POST /quiz-questions` 建 1 单选 + 1 填空
4. `POST /assessments` 挂 3 题（60/20/20）→ `POST /assessments/{id}/publish`
5. 学生改密 → 存草稿 → 交卷 → 提交代码 → 看报告 → 问助教 → 看画像
6. 教师关闭考核 → 填空阅卷打分 → 发布成绩 → 学生看成绩

---

## 9. 收尾提示

- 本次跑的 MySQL 与后端是**前台进程**（后台任务），机器重启后不会自动启动；重新验收需按 §8 再起一次。
- 演示数据在 `innovation_space` 库里，含 1 名学生、1 道题、2 道客观题、3 场考核、4 条提交。要干净重来：`DROP DATABASE innovation_space` 后重跑迁移。
- 仓库侧零改动：`git status` 干净，没有新增未跟踪文件，`node_modules` 与 `target` 均被忽略。
- 建议同步更新 `docs/user-manual.md` 附录 A：第 2、3、5 条（自动代交、提交次数、练习是否计入画像）现在有实测答案，可以定稿；第 4 条（报告缓存）仍需配好 AI 才能验证。
