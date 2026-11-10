# 端到端试跑记录（test-notes）

| 项目 | 内容 |
| --- | --- |
| 记录时间 | 2026-11-10 |
| 记录人 | 徐墺锟（测试） |
| 目的 | 用演示账号走一遍「做题 → 提交 → 看报告 → 问助教 → 看画像」，记录卡住或看不懂的地方 |
| 结论 | **本次环境跑不起来，端到端流程未执行**。原因见第 1 节；第 3 节给出环境就绪后的执行清单 |

---

## 1. 结论：环境缺三样东西，流程无法开始

| 依赖 | 项目要求 | 本机实测 | 能否跑 |
| --- | --- | --- | --- |
| JDK | 21（`backend/pom.xml`：`java.version=21`、`maven.compiler.release=21`） | **17.0.21**（`F:\JAVA`，`./mvnw -v` 输出 `Java version: 17.0.21.0.2`） | ❌ 后端编译不过 |
| MySQL | 8.0 | 未安装客户端，`netstat` 无 3306/3307 监听 | ❌ 无库可连 |
| Docker | Judge0 与 MySQL 都建议用容器 | `docker` 命令不存在 | ❌ 判题机起不来 |
| Node / 包管理 | Node 24、pnpm 11 | Node 22.22.2、npm 10.9.7、corepack 可拉到 pnpm 12.18.0 | ⚠️ 前端能起，但没有后端可用 |
| 演示账号 | —— | **仓库里没有任何种子用户**（迁移只插七个方向和一条 bootstrap 锁行） | ❌ 没有账号可登录 |
| AI 密钥 | `AI_API_KEY` / `AI_BASE_URL` / `AI_MODEL` | 未配置（`AI_PROVIDER` 默认 `disabled`） | ❌ AI 批改与助教会显示「暂不可用」 |
| Judge0 Token | `OJ_API_TOKEN` | 未配置（`OJ_PROVIDER` 默认 `mock`） | ❌ 提交无法真实判定 |

Maven 本身可用（`Apache Maven 3.9.16`），说明**只是 JDK 版本不够**，不是构建工具缺失。把 JDK 换成 21 后后端才有编译的可能。

> 注意：`OJ_PROVIDER` 默认是 `mock`、`AI_PROVIDER` 默认是 `disabled`，所以**即使把服务起来，不配这两项也走不通"真实判题 + AI 批改"**。演示环境必须显式配 `OJ_PROVIDER=judge0` 与 `AI_PROVIDER=openai-compatible`，否则看到的只是 mock 结果或"暂不可用"提示。

---

## 2. 我实际做过的检查（可复现）

```bash
# 1) JDK 版本（结论：17，不满足 21）
cd backend && ./mvnw -v
#   Apache Maven 3.9.16
#   Java version: 17.0.21.0.2, vendor: Oracle Corporation, runtime: F:\JAVA

# 2) 数据库端口（结论：无监听）
netstat -an | grep -E ":3306|:3307"

# 3) 容器与数据库客户端（结论：都不存在）
docker --version      # command not found
mysql --version       # command not found

# 4) 前端工具链（结论：pnpm 可由 corepack 提供）
node -v               # v22.22.2
corepack pnpm -v      # 12.18.0（首次会下载）
```

**没有做的事**（避免污染仓库或做出无意义的结论）：

- 没有 `pnpm install` 装前端依赖：装了也只能连 mock，而 README 明确"演示不用模拟数据"，跑 mock 得到的结论对验收没有意义。
- 没有改 `application.yml`、没有把 `OJ_PROVIDER` 改成 `mock` 来"跑通"流程：那等于自己骗自己。
- 没有安装 JDK 21 / MySQL / Docker：属于改本机环境，需要先和队里确认，不擅自装。

---

## 3. 环境就绪后的执行清单（按这个顺序，10 分钟内能走完）

> 下面命令里的密码一律交互输入或用环境变量，**不要写进脚本、不要提交**。

### 3.1 起库与迁移

```bash
# 开发库（示例端口 3307，避免占用本机 3306）
docker run -d --name aispace-dev-mysql \
  -p 127.0.0.1:3307:3306 \
  -e MYSQL_ROOT_PASSWORD='<一次性开发密码>' \
  -e MYSQL_DATABASE=innovation_space \
  -e MYSQL_USER=innovation_app \
  -e MYSQL_PASSWORD='<开发库密码>' \
  mysql:8.0

cd backend
export DB_HOST=127.0.0.1 DB_PORT=3307 DB_NAME=innovation_space DB_USERNAME=innovation_app
read -rsp 'DB password: ' DB_PASSWORD; printf '\n'; export DB_PASSWORD
export SPRING_PROFILES_ACTIVE=dev

./mvnw clean package -DskipTests
java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar --app.migration.mode=migrate
```

### 3.2 建第一个管理员（没有它什么都做不了）

```bash
# 只在当前 shell 临时导出，不要写进 .env / 脚本 / Git
ADMIN_ACCOUNT='<管理员账号>' ADMIN_PASSWORD='<强口令>' ADMIN_DISPLAY_NAME='<显示名>' \
  java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar --app.bootstrap.mode=admin
```

同时要设 `MEMBER_INITIAL_PASSWORD`（新建学生与重置密码用的统一初始口令），否则建出来的学生无法登录。

### 3.3 起服务与判题机

```bash
# Judge0（按 deploy/judge0/README.md 起，注意 cgroup v2 主机必须开 rlimit 回退）
export OJ_PROVIDER=judge0
export OJ_BASE_URL=http://127.0.0.1:2358
export OJ_API_TOKEN='<Judge0 Token>'
export OJ_RLIMIT_FALLBACK=true

# AI（不配就走不到 AI 批改与助教）
export AI_SCORING_ENABLED=true
export AI_PROVIDER=openai-compatible
export AI_BASE_URL='<HTTPS 兼容端点>'
export AI_API_KEY='<密钥>'
export AI_MODEL=mimo-v2.6-flash

java -jar target/innovation-space-backend-0.1.0-SNAPSHOT.jar

cd ../frontend && corepack pnpm install && corepack pnpm dev   # http://localhost:5173
```

### 3.4 造一套最小演示数据（没有现成演示账号）

1. 管理员登录 → 人员管理 → 添加学生 1 名（记下学号与统一初始口令）。
2. 题库管理 → 创建题目：选**函数传参**模式，配 2 个正式测试点 + 2 个练习测试点（同一组数据即可，便于对比练习与正式判题的差异）。
3. 客观题库 → 创建 1 道单选、1 道填空（填空用来验证人工阅卷链路）。
4. 考核管理 → 创建考核 → 挂上面 3 道题 → 保存草稿 → 进详情 → **发布**。
5. 学生登录（首次会强制改密）→ 走下面的验收路径。

### 3.5 验收路径（逐条打勾，卡住就记到第 5 节）

- [ ] 学生登录 → 强制改密 → 进入个人首页
- [ ] 考核列表 → 考核详情 → 「进入考试」→ 试卷页能看到 1 单选 + 1 填空 + 1 程序题
- [ ] 试卷页改答案 → 等 2 秒 → 出现「草稿 HH:MM:SS 已自动保存」
- [ ] 程序题「去作答」→ 写代码 → **试运行**（应抽到 1 个正式测试点）→ 连续点 11 次，验证第 11 次提示限流
- [ ] 「提交代码」→ 等状态到「已判定」→ 结果区出现「参考测试点 · 第 N 个」
- [ ] 提交记录 → 「批改报告」→ 看错因解析/AI 点评、改进建议、推荐下一题
- [ ] 报告页「问 AI 助教」→ 连续追问 2 轮 → 刷新页面，历史仍在
- [ ] 独立「AI 助教」→ 新建会话 → 关联题目 → 提问 → 重命名 → 删除
- [ ] 「学习画像」→ 有数据后看 4 个数字卡、薄弱知识点、推荐练习；再换一个新学生看空状态
- [ ] 试卷「交卷」→ 客观题锁定 → 学生「查看成绩」应显示「成绩待公布」
- [ ] 教师：填空阅卷 → 给填空打分 → 发布成绩 → 学生端能看到分数、逐题对错、评语、解析
- [ ] 教师：提交详情 → 人工批阅改分 → 确认人工分覆盖自动分
- [ ] 管理员：看板 7d/30d 切换、需要处理、方向对比、最近动态是否都有数

---

## 4. 静态阅读发现的问题（未运行验证，供确认）

这些不是"跑出来的 bug"，是读代码时看到的不一致，建议在实跑时顺带确认：

| # | 现象 | 位置 | 影响 |
| --- | --- | --- | --- |
| 1 | 路由文件顶部注释仍写「本轮只搭骨架：所有业务页面都是占位组件」，与现状（业务页面已全部实现）不符 | `frontend/src/router/routes.ts` 第 3-4 行 | 误导新同学和评委，属陈旧注释，建议清理 |
| 2 | 考核详情页的开始/截止时间直接输出原始字符串，而列表页已统一走 `formatDateTime` | `AssessmentDetailView.vue`（`{{ assessment.startAt }}` / `{{ assessment.endAt }}`）vs `AssessmentListView.vue` | 同一场考核在两个页面显示的时间格式可能不一致 |
| 3 | 考核列表、题库列表调接口时没传分页参数，前端筛选/搜索只作用于已加载的那一页 | `frontend/src/api/modules/oj.ts`（`listAssessments()`、`listAssessmentProblems()` 无 page/size） | 数据量超过默认页大小时，"按难度筛选"会漏掉后面的数据 |
| 4 | 「调整管辖方向」要求手输方向 ID，不是下拉选择 | `LeaderListView.vue`（`handleUpdate`） | 容易填错，且管理员看不到 ID 对应哪个方向 |
| 5 | 仓库没有 CI 配置 | 无 `.github/workflows` | 测试靠手动跑，回归风险高 |
| 6 | 仓库未选择开源许可证（README 已注明） | `README.md` 末节 | 若要公开分发需先确认 Judge0 的许可证义务 |

> 第 1 条属于注释与代码不同步，不影响功能；第 2、3 条建议实跑确认后再决定是否记为缺陷。

---

## 5. 需要跑通后才能定稿的手册条目

`docs/user-manual.md` 附录 A 里的 10 条待确认项中，下面 3 条**只能靠实跑确认**，读代码定不了：

1. **考核截止后的自动代交**（手册 1.4）：到截止时间不交卷，是否真的自动代交、代交后学生能否看到答题卡。
2. **考核期间是否限制正式提交次数**（手册 1.5）：前端没限制，需要连续提交验证后端是否拦截。
3. **题库练习是否计入学习画像**（手册 1.8）：先只做练习、不做正式提交，看画像是否仍为空状态。

另外两条与配置有关，实跑时一并确认：

4. **AI 不可用时的表现**（手册 1.6/1.7）：把 `AI_PROVIDER` 设回 `disabled`，确认报告页与助教页显示"暂不可用 + 原因"而不是空白或假结果。
5. **报告缓存**（手册 1.6）：同一份提交连续刷新，确认第二次出现「命中缓存」且内容一致。

---

## 6. 下次试跑前要准备的东西（给队里）

- [ ] 一台装了 JDK 21 的机器（或把本机 JDK 升到 21）
- [ ] MySQL 8.0（本机安装或 Docker）
- [ ] Docker + Judge0 栈（`deploy/judge0/`），并确认主机是 cgroup v1 还是 v2
- [ ] 一个可用的 openai-compatible 端点与密钥（Mimo），**只放本机环境变量**
- [ ] 统一初始口令 `MEMBER_INITIAL_PASSWORD` 的值（队内口头约定，不写进仓库）
- [ ] 约定一个演示学号段（如 `DEMO202600xx`），避免和真实学号冲突

> 提醒：演示数据建好之后，**不要把数据库导出、`.env`、Token 提交到仓库**。`.env` 已在 `.gitignore` 里，数据库备份没有，需要自觉。
