#!/usr/bin/env python3
"""把题目定义编译成学院 OJ 可直接批量导入的 JSON。

用法：
    python3 generate_bank.py                 # 生成 out/oj-problems-30.json
    python3 generate_bank.py --limit 10      # 只生成前 10 道
    python3 generate_bank.py -o out/custom.json

输出结构严格遵循 POST /api/v1/assessment-problems/import 的契约：
    {"problems": [CreateAssessmentProblemRequest, ...]}

脚本同时做三类自检：
1. 字段长度、枚举、数值区间等契约约束；
2. examples 中手写的 output 必须与参考解一致；
3. 每个测试点都由参考解现算期望输出，避免题面与数据互相矛盾。
"""

import argparse
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

import problems_easy
import problems_medium
import problems_hard

LANGUAGES = {"java", "python", "cpp", "javascript", "go", "rust"}
DIFFICULTIES = {"EASY", "MEDIUM", "HARD"}
MAX_TEST_CASES = 100
MAX_CASE_BYTES = 1_000_000


class BankError(Exception):
    pass


def check(condition, message):
    if not condition:
        raise BankError(message)


def text_of(problem, field, max_len):
    value = problem.get(field)
    check(isinstance(value, str) and value.strip(), "%s: 字段 %s 缺失或为空" % (problem["title"], field))
    check(len(value) <= max_len, "%s: 字段 %s 超过 %d 字符" % (problem["title"], field, max_len))
    return value


def build_problem(problem):
    """校验单题定义并补齐导入所需的字段。"""
    title = text_of(problem, "title", 120)
    text_of(problem, "summary", 300)
    text_of(problem, "description", 20000)
    text_of(problem, "inputFormat", 5000)
    text_of(problem, "outputFormat", 5000)

    difficulty = problem.get("difficulty")
    check(difficulty in DIFFICULTIES, "%s: difficulty 非法" % title)

    tags = problem.get("tags") or []
    check(isinstance(tags, list) and 1 <= len(tags) <= 20, "%s: tags 数量非法" % title)
    for tag in tags:
        check(isinstance(tag, str) and tag.strip() and len(tag) <= 40, "%s: 标签非法 %r" % (title, tag))

    time_limit = problem.get("timeLimitMs")
    check(isinstance(time_limit, int) and 100 <= time_limit <= 30000, "%s: timeLimitMs 越界" % title)
    memory_limit = problem.get("memoryLimitKb")
    check(isinstance(memory_limit, int) and 1024 <= memory_limit <= 1048576, "%s: memoryLimitKb 越界" % title)

    languages = problem.get("allowedLanguages") or []
    check(isinstance(languages, list) and 1 <= len(languages) <= 6, "%s: allowedLanguages 数量非法" % title)
    check(all(lang in LANGUAGES for lang in languages), "%s: 存在不支持的语言" % title)

    examples = problem.get("examples") or []
    check(1 <= len(examples) <= 20, "%s: examples 数量非法" % title)
    clean_examples = []
    solve = problem["solve"]
    for index, example in enumerate(examples):
        check(set(example.keys()) <= {"input", "output", "explanation"}, "%s: 样例 %d 含未知字段" % (title, index))
        sample_input = example.get("input")
        sample_output = example.get("output")
        check(isinstance(sample_input, str) and sample_input.strip(), "%s: 样例 %d 输入为空" % (title, index))
        check(isinstance(sample_output, str) and sample_output.strip(), "%s: 样例 %d 输出为空" % (title, index))
        produced = solve(sample_input)
        check(
            produced.strip() == sample_output.strip(),
            "%s: 样例 %d 的 output 与参考解不一致（参考解得到 %r）" % (title, index, produced.strip()),
        )
        item = {"input": sample_input, "output": sample_output}
        if example.get("explanation"):
            item["explanation"] = example["explanation"]
        clean_examples.append(item)

    inputs = problem.get("inputs") or []
    check(len(inputs) >= 1, "%s: 缺少测试输入" % title)

    cases = []
    seen = set()
    for raw in inputs:
        check(isinstance(raw, str), "%s: 测试输入必须是字符串" % title)
        if raw in seen:
            continue
        seen.add(raw)
        try:
            expected = solve(raw)
        except Exception as exc:  # noqa: BLE001 - 参考解异常必须暴露
            raise BankError("%s: 参考解在测试输入上抛异常：%s" % (title, exc)) from exc
        check(isinstance(expected, str), "%s: 参考解必须返回字符串" % title)
        expected = expected.strip()
        check(expected != "", "%s: 参考解返回空输出" % title)
        check(len(raw.encode("utf-8")) <= MAX_CASE_BYTES, "%s: 单个测试输入超过 1MB" % title)
        check(len(expected.encode("utf-8")) <= MAX_CASE_BYTES, "%s: 单个期望输出超过 1MB" % title)
        cases.append({"input": raw, "expectedOutput": expected})

    check(1 <= len(cases) <= MAX_TEST_CASES, "%s: 测试点数量 %d 非法" % (title, len(cases)))

    # 练习测试点用于学生自测，取最小的两个输入，避免把大数据暴露在题面里。
    practice = sorted(cases, key=lambda item: len(item["input"]))[:2]
    practice = [{"input": item["input"], "expectedOutput": item["expectedOutput"]} for item in practice]

    return {
        "title": title,
        "summary": problem["summary"],
        "description": problem["description"],
        "inputFormat": problem["inputFormat"],
        "outputFormat": problem["outputFormat"],
        "examples": clean_examples,
        "difficulty": difficulty,
        "tags": list(tags),
        "timeLimitMs": time_limit,
        "memoryLimitKb": memory_limit,
        "allowedLanguages": list(languages),
        "assessmentTestCases": cases,
        "practiceTestCases": practice,
    }


def collect():
    problems = []
    for module in (problems_easy, problems_medium, problems_hard):
        problems.extend(module.PROBLEMS)
    return problems


def main():
    parser = argparse.ArgumentParser(description="生成学院 OJ 批量导入 JSON")
    parser.add_argument("-o", "--output", default=os.path.join(HERE, "out", "oj-problems-30.json"))
    parser.add_argument("--limit", type=int, default=0, help="只导出前 N 道题，0 表示全部")
    args = parser.parse_args()

    raw_problems = collect()
    if args.limit:
        raw_problems = raw_problems[: args.limit]

    built = []
    titles = set()
    for problem in raw_problems:
        item = build_problem(problem)
        key = item["title"].strip().lower()
        check(key not in titles, "题目重名：%s" % item["title"])
        titles.add(key)
        built.append(item)

    payload = {"problems": built}
    body = json.dumps(payload, ensure_ascii=False, indent=2)
    size = len(body.encode("utf-8"))
    check(size <= 5 * 1024 * 1024, "导出文件超过 5MB，请分批导入")

    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as handle:
        handle.write(body)
        handle.write("\n")

    stats = {}
    for item in built:
        stats[item["difficulty"]] = stats.get(item["difficulty"], 0) + 1
    total_cases = sum(len(item["assessmentTestCases"]) for item in built)

    print("已生成 %s" % args.output)
    print("题目数量：%d（%s）" % (len(built), "，".join("%s=%d" % kv for kv in sorted(stats.items()))))
    print("测试点总数：%d" % total_cases)
    print("文件大小：%.1f KB" % (size / 1024.0))


if __name__ == "__main__":
    try:
        main()
    except BankError as error:
        print("校验失败：%s" % error, file=sys.stderr)
        sys.exit(1)
