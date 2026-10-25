# OJ 题库批量导入包

为学院科创空间管理系统的 OJ 模块准备的题库与转换工具。产出文件可以直接通过
`POST /api/v1/assessment-problems/import` 批量导入，无需逐题手工录入。

> **重要（2026-10 起的契约变化）**：导入接口新建题目统一为**函数传参（FUNCTION）模式**，
> 每题必须给出函数签名，测试点 `input` 填写与参数列表匹配的 JSON 参数数组。
> 下表的 `out/oj-problems-30.json` 及 `generate_bank.py` 生成链路仍是旧的
> 标准输入输出（STDIO）格式，**当前无法通过导入接口**，待转换（欠账）。
> 新题请以 `oj-import-template.json` 为模板手工编写。

## 交付物

| 文件 | 说明 |
| --- | --- |
| `oj-import-template.json` | **导入模板**。两道示例题覆盖 `string → bool`、`int[] → int[]` 两种典型签名，可整体通过前端与后端校验 |
| `out/oj-problems-30.json` | 旧版 STDIO 格式题库（30 道题、222 个正式测试点、60 个练习测试点，174.8 KB）。存量格式，待转换为函数传参模式后再导入 |
| `problems_easy.py` / `problems_medium.py` / `problems_hard.py` | 题目定义：题面、参考解、测试输入（STDIO 口径） |
| `generate_bank.py` | 编译题目定义，生成导入 JSON，并做契约自检（STDIO 口径） |
| `validate_bank.py` | 独立校验器，规则与后端 `AssessmentProblemServiceImpl` 一致 |
| `check_weak_solutions.py` | 弱解检测：用常见错误算法验证测试数据的区分度 |
| `convert_kattis.py` | 把 Kattis / ICPC 风格开源题包转成本系统导入 JSON |

## 题库内容

难度分布 EASY 12 / MEDIUM 12 / HARD 6，覆盖入门语法、数组字符串、排序查找、栈与哈希、
贪心、动态规划、图论、并查集、线段树、单调队列。

| # | 题目 | 难度 | 测试点 | 主要标签 |
| --- | --- | --- | --- | --- |
| 1 | A+B 问题 | EASY | 7 | 入门、数学、输入输出 |
| 2 | 奇偶判定 | EASY | 7 | 入门、数学、条件判断 |
| 3 | 最大公约数 | EASY | 7 | 入门、数学、数论 |
| 4 | 素数判定 | EASY | 8 | 入门、数学、数论 |
| 5 | 数组求和与最大值 | EASY | 6 | 入门、数组、遍历 |
| 6 | 字符串反转 | EASY | 6 | 入门、字符串 |
| 7 | 回文串判定 | EASY | 8 | 入门、字符串、双指针 |
| 8 | 二分查找 | EASY | 5 | 入门、二分查找、数组 |
| 9 | 斐波那契数列 | EASY | 8 | 入门、递推、动态规划 |
| 10 | 统计字符出现次数 | EASY | 6 | 入门、字符串、计数 |
| 11 | 三个数排序 | EASY | 6 | 入门、排序、条件判断 |
| 12 | 阶乘末尾零的个数 | EASY | 8 | 入门、数学、数论 |
| 13 | 最大子段和 | MEDIUM | 9 | 动态规划、数组、贪心 |
| 14 | 区间合并 | MEDIUM | 9 | 排序、贪心、区间 |
| 15 | 括号匹配 | MEDIUM | 7 | 栈、字符串、括号匹配 |
| 16 | 两数之和 | MEDIUM | 6 | 哈希表、数组、查找 |
| 17 | 快速幂取模 | MEDIUM | 6 | 数学、快速幂、位运算 |
| 18 | 统计素数个数 | MEDIUM | 7 | 数论、筛法、素数 |
| 19 | 0-1 背包 | MEDIUM | 10 | 动态规划、背包 |
| 20 | 最长上升子序列 | MEDIUM | 9 | 动态规划、二分查找、序列 |
| 21 | 岛屿数量 | MEDIUM | 8 | 图论、深度优先搜索、广度优先搜索 |
| 22 | 单源最短路径 | MEDIUM | 9 | 图论、最短路、堆 |
| 23 | 连通块数量 | MEDIUM | 8 | 图论、并查集、连通性 |
| 24 | 编辑距离 | MEDIUM | 6 | 动态规划、字符串、编辑距离 |
| 25 | 拓扑排序（字典序最小） | HARD | 9 | 图论、拓扑排序、堆 |
| 26 | 线段树区间求和 | HARD | 6 | 线段树、树状数组、数据结构 |
| 27 | 最长公共子序列 | HARD | 9 | 动态规划、字符串、最长公共子序列 |
| 28 | 矩阵快速幂求斐波那契 | HARD | 7 | 数学、矩阵快速幂、递推 |
| 29 | 最小生成树 | HARD | 8 | 图论、最小生成树、并查集 |
| 30 | 滑动窗口最大值 | HARD | 7 | 单调队列、滑动窗口、数组 |

每道题都包含：

- 公开题面：`description`、`inputFormat`、`outputFormat`、`examples`；
- 隐藏测试点 `assessmentTestCases`：判题使用，学生不可见；
- 练习测试点 `practiceTestCases`：取最小的两个输入，供学生自测；
- 时间/内存限制与允许语言（`cpp`、`java`、`python`）。

## 导入模板字段规则

根节点只有一个键 `problems`（数组，1..100 题，单文件 ≤5MB，整批原子写入，标题不区分大小写去重）。
每题字段（多一个键、少一个必填键都会整批被拒）：

| 字段 | 必填 | 规则 |
| --- | --- | --- |
| `title` | 是 | ≤120 字符；同一批内不能重名（忽略大小写与首尾空格） |
| `summary` | 是 | 一句话摘要，≤300 字符 |
| `description` | 是 | 题面，≤20000 字符 |
| `inputFormat` | 是 | 描述函数参数，≤5000 字符 |
| `outputFormat` | 是 | 描述返回值，≤5000 字符 |
| `examples` | 是 | 数组 ≤20 项，每项 `{input, output, explanation?}`，纯展示文本 |
| `difficulty` | 是 | `EASY` / `MEDIUM` / `HARD` |
| `tags` | 是 | ≤20 个，每个 ≤40 字符 |
| `timeLimitMs` | 是 | 100..30000 |
| `memoryLimitKb` | 是 | 1024..1048576 |
| `allowedLanguages` | 是 | 1..6 个，取值 `java` / `python` / `cpp` / `javascript` / `go` / `rust` |
| `judgeMode` | 否 | 只能是 `FUNCTION`（缺省即 FUNCTION；新建不允许 STDIO） |
| `functionName` | 是 | 匹配 `^[A-Za-z_][A-Za-z0-9_]{0,63}$` |
| `parameterTypes` | 是 | 0..4 个，取值 `int` / `long` / `bool` / `string` / `int[]` / `long[]` / `string[]` / `int[][]` |
| `returnType` | 是 | 同 `parameterTypes` 的取值 |
| `assessmentTestCases` | 是 | 1..100 个正式考核隐藏测试点 |
| `practiceTestCases` | 否 | ≤100 个题库练习测试点，向学生公开输入与期望输出 |

测试点每项只有两个键，值都是字符串：

- `input`：**JSON 参数数组文本**，元素个数与 `parameterTypes` 一一对应且类型匹配。
  例如签名 `(int, int[])` 时 `"[3, [1, 2, 3]]"`；签名 `(string)` 时 `"[\"level\"]"`。
- `expectedOutput`：**返回值的紧凑 JSON 文本**。`int`/`long` 写 `3`；`bool` 写 `true`；
  `string` 写带引号的 `"abc"`（不带引号也能被归一化补引号）；数组写 `[1,2,4]`；
  矩阵写 `[[1,2],[3,4]]`。

## 导入步骤

### 方式一：前端页面

1. 使用学院管理员或方向负责人账号登录；
2. 进入「OJ 题库」（`/oj/problems`）；
3. 点击「导入」，选择导入文件（新题请复制 `oj-import-template.json` 修改；`out/oj-problems-30.json` 为存量 STDIO 格式，暂不可导入）；
4. 页面会先做本地预览校验，确认题名和数量后提交。

### 方式二：命令行

```bash
# 1. 登录拿 accessToken
curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"account":"你的账号","password":"你的密码"}'

# 2. 导入（Idempotency-Key 用任意 UUID，同一批重试要复用同一个值）
curl -s -X POST http://localhost:8080/api/v1/assessment-problems/import \
  -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <accessToken>' \
  -H 'Idempotency-Key: 7f3c1e2a-9b4d-4c8e-a1f6-2d5b8c0e4a91' \
  --data-binary @out/oj-problems-30.json
```

成功响应：

```json
{"code":"OK","message":"Success","data":{"importedCount":30,"problemIds":["1","2","..."],"errors":[]}}
```

### 导入后为什么学生看不到

导入的题目状态是 `DRAFT`。按当前后端实现，学生端只列出 `PUBLISHED` 题目，而题目状态
只能通过「创建考核 → 关联题目 → 发布考核」这条链路切换（`AssessmentServiceImpl` 在
publish 时把关联题目置为 `PUBLISHED`）。所以完整流程是：

1. 导入题库（本题库，全部为 DRAFT）；
2. 创建考核，从题库中挑选题目并设置顺序；
3. 发布考核，被关联的题目自动变为 PUBLISHED，学生即可练习和作答。

## 校验与重新生成

```bash
cd tools/oj-bank

# 校验任意导入文件（含别人改过的文件）
python3 validate_bank.py out/oj-problems-30.json

# 重新生成全部题目
python3 generate_bank.py

# 只导出前 10 题，或指定输出路径
python3 generate_bank.py --limit 10 -o out/oj-problems-10.json

# 弱解检测：确认错误算法会被测试数据判错
python3 check_weak_solutions.py out/oj-problems-30.json
```

`generate_bank.py` 在生成时会强制检查：

- 字段长度、枚举值、数值区间与后端契约一致；
- 每道题的 `examples.output` 必须与参考解算出的结果完全一致；
- 每个测试点由参考解现算期望输出，杜绝题面与数据矛盾；
- 单题测试点不超过 100 个、单个用例不超过 1MB、整包不超过 5MB。

## 接入开源题库

### 可用的开源题包来源

| 来源 | 地址 | 说明 |
| --- | --- | --- |
| Kattis problem packages | https://open.kattis.com/problem-packages | ICPC 区域赛题包，标准 problem package 格式，多数以 CC BY-SA 发布，含完整 secret 数据 |
| CSES Problem Set | https://cses.fi/problemset/ | 300 道系统化训练题，覆盖入门到进阶，适合教学 |
| DMOJ | https://dmoj.ca/ | 部分题目的数据在 DMOJ 组织下的 GitHub 仓库 |
| HydroOJ | https://github.com/hydro-dev/Hydro | 支持导入/导出题目包，社区题库可复用 |

> 注意：不同题包的许可证不同，商用或公开发布前请逐个查看题包内的 LICENSE。
> 本项目当前环境直连 GitHub 与 cses.fi 会被网络重置，未能自动拉取在线题包，
> 因此主交付物是原创题面 + 程序化生成的测试数据，不含第三方版权内容。

### 转换命令

```bash
# 单个题包
python3 convert_kattis.py ./packages/hello-world -o out/hello.json

# 批量转换
python3 convert_kattis.py ./packages/* -o out/open-source-bank.json

# 指定难度和标签
python3 convert_kattis.py ./packages/* -o out/open-source-bank.json \
  --difficulty HARD --tags 图论 最短路
```

转换器会读取 `problem.yaml` 的 `name`、`limits.time`、`limits.memory`，从
`data/sample` 与 `data/secret` 收集测试数据，并把 sample 同时作为练习测试点。
题面优先读 `problem_statement/problem.zh.md`；只有 `.tex` 时会做保守的纯文本提取，
建议转换后人工复核题面。

转换完务必再跑一次 `validate_bank.py`，因为后端导入是**整批原子**的：只要一题不合法，
整批 30 题都不会写入。

## 扩展题库

在 `problems_easy.py` / `problems_medium.py` / `problems_hard.py` 的 `PROBLEMS` 列表里追加一项：

```python
{
    "title": "题名（≤120 字符，同一批不能重名）",
    "summary": "一句话摘要（≤300 字符）",
    "description": "题面（≤20000 字符）",
    "inputFormat": "输入格式（≤5000 字符）",
    "outputFormat": "输出格式（≤5000 字符）",
    "examples": [{"input": "1 2", "output": "3", "explanation": "可选"}],
    "difficulty": "EASY",              # EASY / MEDIUM / HARD
    "tags": ["入门", "数学"],           # ≤20 个，每个 ≤40 字符
    "timeLimitMs": 1000,               # 100..30000
    "memoryLimitKb": 262144,           # 1024..1048576
    "allowedLanguages": ["cpp", "java", "python"],
    "solve": 你的参考解函数,             # 输入 str，返回 str
    "inputs": ["测试输入1", "测试输入2"],  # 期望输出由参考解自动算出
}
```

然后重新执行 `python3 generate_bank.py` 即可。

## 已知限制

- 单批最多 100 题、5MB；超过时请拆分文件分批导入（每批使用不同的幂等键）。
- 题面为纯文本，`description` 中的 Markdown 标记会按原样显示。
- 判题走 Judge0，新建题目统一函数传参模式：测试点 `input` 为 JSON 参数数组、`expectedOutput` 为返回值 JSON 文本；标准输入输出（STDIO）仅存量题目保留，交互题、SPJ 题暂不支持。
- 练习测试点会向学生展示输入与期望输出，因此只放小规模样例。
