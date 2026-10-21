#!/usr/bin/env python3
"""把 Kattis / ICPC 风格的开源题包转换成本系统的题库导入 JSON。

开源题包通常长这样（Kattis problem package format）：

    my-problem/
      problem.yaml          # name、limits.time、limits.memory
      problem_statement/
        problem.zh.md       # 或 problem.en.tex
      data/
        sample/01.in 01.ans
        secret/01.in 01.ans

用法：
    python3 convert_kattis.py ./my-problem -o out/my-problem.json
    python3 convert_kattis.py ./packages/* -o out/open-source-bank.json
    python3 convert_kattis.py ./my-problem --difficulty HARD --tags 图论 最短路

说明：
- 测试数据会按 sample → secret 的顺序合并为 assessmentTestCases；
- sample 同时作为 practiceTestCases（学生可见）；
- 题面优先读取 markdown；只有 .tex 时会做保守的纯文本提取，建议人工复核；
- 转换结果仍需通过 validate_bank.py 校验后再导入。
"""

import argparse
import glob
import json
import os
import re
import sys

ANSWER_SUFFIXES = (".ans", ".out")
LANGUAGES = ["cpp", "java", "python"]


def read_text(path):
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        return handle.read()


def parse_yaml_light(text):
    """只解析 problem.yaml 里需要的几个标量，避免引入 PyYAML 依赖。"""
    result = {"name": None, "time": None, "memory": None}
    section = None
    for raw in text.splitlines():
        line = raw.split("#", 1)[0].rstrip()
        if not line.strip():
            continue
        if re.match(r"^\S", line):
            match = re.match(r"^([A-Za-z_]+):\s*(.*)$", line)
            if match:
                key, value = match.group(1), match.group(2).strip()
                section = key if not value else None
                if key == "name" and value:
                    result["name"] = value.strip("\"'")
        elif section == "limits":
            match = re.match(r"^\s+([A-Za-z_]+):\s*(.+)$", line)
            if match:
                key, value = match.group(1), match.group(2).strip()
                if key in ("time", "time_limit"):
                    result["time"] = value
                elif key in ("memory", "memory_limit"):
                    result["memory"] = value
    return result


def strip_latex(text):
    """把 LaTeX 题面粗加工成纯文本，保留段落结构。"""
    text = re.sub(r"(?m)^\s*%.*$", "", text)
    text = re.sub(r"\\begin\{verbatim\}(.*?)\\end\{verbatim\}", r"\1", text, flags=re.S)
    text = re.sub(r"\\(?:section|subsection|subsubsection)\*?\{([^}]*)\}", r"\n## \1\n", text)
    text = re.sub(r"\\(?:textbf|textit|emph|texttt)\{([^}]*)\}", r"\1", text)
    text = re.sub(r"\\item\s*", "- ", text)
    text = re.sub(r"\\[a-zA-Z]+\*?(?:\[[^\]]*\])?", "", text)
    text = text.replace("{", "").replace("}", "")
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def load_statement(package):
    candidates = [
        os.path.join(package, "problem_statement", "problem.zh.md"),
        os.path.join(package, "problem_statement", "problem.md"),
        os.path.join(package, "problem_statement", "statement.md"),
        os.path.join(package, "problem.md"),
        os.path.join(package, "statement.md"),
    ]
    for path in candidates:
        if os.path.isfile(path):
            return read_text(path), "markdown"

    tex_files = sorted(glob.glob(os.path.join(package, "problem_statement", "*.tex")))
    if not tex_files:
        tex_files = sorted(glob.glob(os.path.join(package, "*.tex")))
    if tex_files:
        preferred = [p for p in tex_files if ".zh." in p] or tex_files
        return strip_latex(read_text(preferred[0])), "latex"
    return "", "none"


def split_sections(statement):
    """按 Input/Output 小标题切出输入输出说明，切不出来就返回整段。"""
    patterns = [
        (
            r"(?im)^#{0,6}\s*(?:input specification|input)\s*$",
            r"(?im)^#{0,6}\s*(?:output specification|output)\s*$",
        ),
        (
            r"(?im)^#{0,6}\s*(?:输入格式|输入描述|输入说明|输入)\s*$",
            r"(?im)^#{0,6}\s*(?:输出格式|输出描述|输出说明|输出)\s*$",
        ),
    ]
    for input_head, output_head in patterns:
        input_match = re.search(input_head, statement)
        if not input_match:
            continue
        rest = statement[input_match.end() :]
        output_match = re.search(output_head, rest)
        if not output_match:
            continue
        input_text = rest[: output_match.start()].strip()
        output_text = rest[output_match.end() :].strip()
        # 输出段落到下一个二级标题或样例说明为止
        cut = re.search(r"(?im)^#{1,6}\s*(?:example|sample|样例|示例|explanation)\b.*$", output_text)
        if cut:
            output_text = output_text[: cut.start()].strip()
        description = statement[: input_match.start()].strip()
        return description, input_text, output_text
    return statement.strip(), "", ""


def collect_cases(package):
    data_dir = os.path.join(package, "data")
    sample, secret = [], []
    for group, bucket in (("sample", sample), ("secret", secret)):
        folder = os.path.join(data_dir, group)
        if not os.path.isdir(folder):
            continue
        for input_path in sorted(glob.glob(os.path.join(folder, "*.in"))):
            stem = input_path[: -len(".in")]
            answer = None
            for suffix in ANSWER_SUFFIXES:
                if os.path.isfile(stem + suffix):
                    answer = stem + suffix
                    break
            if answer is None:
                print("警告：%s 缺少答案文件，已跳过" % input_path, file=sys.stderr)
                continue
            bucket.append({"input": read_text(input_path), "expectedOutput": read_text(answer)})
    return sample, secret


def build_problem(package, difficulty, extra_tags, summary_limit=300):
    yaml_path = os.path.join(package, "problem.yaml")
    meta = parse_yaml_light(read_text(yaml_path)) if os.path.isfile(yaml_path) else {"name": None, "time": None, "memory": None}

    title = meta["name"] or os.path.basename(os.path.normpath(package))
    statement, kind = load_statement(package)
    description, input_text, output_text = split_sections(statement)

    if not description.strip():
        description = "题目原文见开源题包：%s" % title
    if not input_text.strip():
        input_text = "见题目描述。"
    if not output_text.strip():
        output_text = "见题目描述。"

    sample, secret = collect_cases(package)
    if not sample and not secret:
        raise ValueError("%s：没有找到任何测试数据" % package)

    cases = sample + secret
    practice = sample[:2] if sample else cases[:1]

    examples = [
        {"input": case["input"], "output": case["expectedOutput"]} for case in sample[:5]
    ]
    if not examples:
        examples = [{"input": cases[0]["input"], "output": cases[0]["expectedOutput"]}]

    time_seconds = float(meta["time"] or 2)
    memory_mb = int(float(meta["memory"] or 1024))
    time_limit = max(100, min(30000, int(time_seconds * 1000)))
    memory_limit = max(1024, min(1048576, memory_mb * 1024))

    summary = re.sub(r"\s+", " ", description).strip()
    if len(summary) > summary_limit:
        summary = summary[: summary_limit - 1] + "…"

    return {
        "title": title[:120],
        "summary": summary or title,
        "description": description[:20000],
        "inputFormat": input_text[:5000],
        "outputFormat": output_text[:5000],
        "examples": examples,
        "difficulty": difficulty,
        "tags": (["开源题包"] + extra_tags)[:20],
        "timeLimitMs": time_limit,
        "memoryLimitKb": memory_limit,
        "allowedLanguages": list(LANGUAGES),
        "assessmentTestCases": cases[:100],
        "practiceTestCases": practice[:100],
    }


def main():
    parser = argparse.ArgumentParser(description="Kattis 风格开源题包 → 学院 OJ 导入 JSON")
    parser.add_argument("packages", nargs="+", help="题包目录，可用通配符")
    parser.add_argument("-o", "--output", required=True, help="输出 JSON 路径")
    parser.add_argument("--difficulty", default="MEDIUM", choices=["EASY", "MEDIUM", "HARD"])
    parser.add_argument("--tags", nargs="*", default=[], help="附加标签")
    args = parser.parse_args()

    directories = []
    for pattern in args.packages:
        matched = sorted(glob.glob(pattern))
        directories.extend(matched if matched else [pattern])

    problems = []
    for directory in directories:
        if not os.path.isdir(directory):
            print("跳过不存在的目录：%s" % directory, file=sys.stderr)
            continue
        try:
            problems.append(build_problem(directory, args.difficulty, args.tags))
            print("已转换：%s" % os.path.basename(os.path.normpath(directory)))
        except ValueError as error:
            print("失败：%s" % error, file=sys.stderr)

    if not problems:
        print("没有可转换的题包", file=sys.stderr)
        return 1
    if len(problems) > 100:
        print("题包超过 100 个，只保留前 100 个", file=sys.stderr)
        problems = problems[:100]

    payload = json.dumps({"problems": problems}, ensure_ascii=False, indent=2)
    os.makedirs(os.path.dirname(os.path.abspath(args.output)), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as handle:
        handle.write(payload + "\n")
    print("已写出 %s（%d 题，%.1f KB）" % (args.output, len(problems), len(payload.encode("utf-8")) / 1024.0))
    print("下一步：python3 validate_bank.py %s" % args.output)
    return 0


if __name__ == "__main__":
    sys.exit(main())
