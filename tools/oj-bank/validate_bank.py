#!/usr/bin/env python3
"""独立校验 OJ 导入 JSON，规则与后端 AssessmentProblemServiceImpl 保持一致。

后端校验是“全有或全无”：任何一题不合法，整批都不会写入。
因此在导入前先跑一遍本脚本，比在浏览器里反复试错更快。

用法：
    python3 validate_bank.py out/oj-problems-30.json
"""

import json
import sys

MAX_BYTES = 5 * 1024 * 1024
MAX_PROBLEMS = 100
MAX_CASES = 100
MAX_CASE_CHARS = 1_000_000
LANGUAGES = {"java", "python", "cpp", "javascript", "go", "rust"}
DIFFICULTIES = {"EASY", "MEDIUM", "HARD"}
PROBLEM_FIELDS = {
    "title",
    "summary",
    "description",
    "inputFormat",
    "outputFormat",
    "examples",
    "difficulty",
    "tags",
    "timeLimitMs",
    "memoryLimitKb",
    "allowedLanguages",
    "assessmentTestCases",
    "practiceTestCases",
}


def utf16_len(value):
    """Java String.length() 按 UTF-16 码元计数，这里保持一致。"""
    return len(value.encode("utf-16-le")) // 2


class Validator:
    def __init__(self):
        self.errors = []

    def fail(self, index, code, detail):
        self.errors.append({"index": index, "code": code, "detail": detail})

    def text(self, value, limit, label, index):
        if not isinstance(value, str):
            self.fail(index, "INVALID_PROBLEM", "%s 必须是字符串" % label)
            return False
        if not value.strip():
            self.fail(index, "INVALID_PROBLEM", "%s 不能为空白" % label)
            return False
        if utf16_len(value) > limit:
            self.fail(index, "INVALID_PROBLEM", "%s 超过 %d 字符" % (label, limit))
            return False
        return True

    def test_cases(self, value, required, label, index):
        if value is None:
            if required:
                self.fail(index, "INVALID_PROBLEM", "%s 缺失" % label)
                return False
            return True
        if not isinstance(value, list):
            self.fail(index, "INVALID_PROBLEM", "%s 必须是数组" % label)
            return False
        if required and not value:
            self.fail(index, "INVALID_PROBLEM", "%s 不能为空" % label)
            return False
        if len(value) > MAX_CASES:
            self.fail(index, "INVALID_PROBLEM", "%s 超过 %d 个" % (label, MAX_CASES))
            return False
        ok = True
        for position, item in enumerate(value):
            if not isinstance(item, dict) or set(item.keys()) != {"input", "expectedOutput"}:
                self.fail(index, "INVALID_PROBLEM", "%s[%d] 字段必须恰好是 input/expectedOutput" % (label, position))
                ok = False
                continue
            for key in ("input", "expectedOutput"):
                raw = item[key]
                if not isinstance(raw, str):
                    self.fail(index, "INVALID_PROBLEM", "%s[%d].%s 必须是字符串" % (label, position, key))
                    ok = False
                elif len(raw) > MAX_CASE_CHARS:
                    self.fail(index, "INVALID_PROBLEM", "%s[%d].%s 超过 1MB" % (label, position, key))
                    ok = False
        return ok

    def problem(self, value, index):
        if not isinstance(value, dict):
            self.fail(index, "INVALID_PROBLEM", "题目必须是对象")
            return
        unknown = set(value.keys()) - PROBLEM_FIELDS
        if unknown:
            self.fail(index, "INVALID_PROBLEM", "存在未知字段：%s" % ", ".join(sorted(unknown)))
            return

        for field, limit in (
            ("title", 120),
            ("summary", 300),
            ("description", 20000),
            ("inputFormat", 5000),
            ("outputFormat", 5000),
        ):
            self.text(value.get(field), limit, field, index)

        examples = value.get("examples")
        if not isinstance(examples, list) or len(examples) > 20:
            self.fail(index, "INVALID_PROBLEM", "examples 必须是数组且不超过 20 项")
        else:
            for position, example in enumerate(examples):
                if not isinstance(example, dict):
                    self.fail(index, "INVALID_PROBLEM", "examples[%d] 必须是对象" % position)
                    continue
                if not set(example.keys()) <= {"input", "output", "explanation"}:
                    self.fail(index, "INVALID_PROBLEM", "examples[%d] 存在未知字段" % position)
                for key in ("input", "output"):
                    if not isinstance(example.get(key), str) or not example.get(key, "").strip():
                        self.fail(index, "INVALID_PROBLEM", "examples[%d].%s 不能为空" % (position, key))
                if example.get("explanation") is not None and not isinstance(example["explanation"], str):
                    self.fail(index, "INVALID_PROBLEM", "examples[%d].explanation 必须是字符串" % position)

        if value.get("difficulty") not in DIFFICULTIES:
            self.fail(index, "INVALID_PROBLEM", "difficulty 必须是 EASY/MEDIUM/HARD")

        tags = value.get("tags")
        if not isinstance(tags, list) or len(tags) > 20:
            self.fail(index, "INVALID_PROBLEM", "tags 必须是数组且不超过 20 项")
        else:
            for tag in tags:
                if not isinstance(tag, str) or not tag.strip() or utf16_len(tag) > 40:
                    self.fail(index, "INVALID_PROBLEM", "存在非法标签 %r" % (tag,))

        for field, low, high in (("timeLimitMs", 100, 30000), ("memoryLimitKb", 1024, 1048576)):
            number = value.get(field)
            if not isinstance(number, int) or isinstance(number, bool) or not low <= number <= high:
                self.fail(index, "INVALID_PROBLEM", "%s 必须是 %d..%d 的整数" % (field, low, high))

        languages = value.get("allowedLanguages")
        if not isinstance(languages, list) or not 1 <= len(languages) <= 6:
            self.fail(index, "INVALID_PROBLEM", "allowedLanguages 必须包含 1..6 种语言")
        elif any(lang not in LANGUAGES for lang in languages):
            self.fail(index, "INVALID_PROBLEM", "allowedLanguages 含不支持的语言")

        self.test_cases(value.get("assessmentTestCases"), True, "assessmentTestCases", index)
        self.test_cases(value.get("practiceTestCases"), False, "practiceTestCases", index)

    def document(self, raw_bytes):
        if len(raw_bytes) > MAX_BYTES:
            self.fail(0, "REQUEST_TOO_LARGE", "文件超过 5MB")
            return None
        try:
            root = json.loads(raw_bytes.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            self.fail(0, "INVALID_JSON", "无法解析 JSON：%s" % exc)
            return None
        if not isinstance(root, dict) or set(root.keys()) != {"problems"}:
            self.fail(0, "INVALID_IMPORT", "顶层必须且只能包含 problems 字段")
            return None
        problems = root["problems"]
        if not isinstance(problems, list) or not problems:
            self.fail(0, "INVALID_IMPORT", "problems 必须是非空数组")
            return None
        if len(problems) > MAX_PROBLEMS:
            self.fail(0, "TOO_MANY_PROBLEMS", "单批最多 %d 题" % MAX_PROBLEMS)
            return None

        titles = set()
        for index, problem in enumerate(problems):
            self.problem(problem, index)
            if isinstance(problem, dict) and isinstance(problem.get("title"), str):
                key = problem["title"].strip().lower()
                if key in titles:
                    self.fail(index, "DUPLICATE_TITLE", "标题重复：%s" % problem["title"])
                titles.add(key)
        return problems


def main():
    if len(sys.argv) != 2:
        print("用法：python3 validate_bank.py <json 文件>", file=sys.stderr)
        return 2

    path = sys.argv[1]
    with open(path, "rb") as handle:
        raw = handle.read()

    validator = Validator()
    problems = validator.document(raw)

    if validator.errors:
        print("校验未通过，共 %d 个问题：" % len(validator.errors))
        for error in validator.errors:
            print("  [%d] %s - %s" % (error["index"], error["code"], error["detail"]))
        return 1

    cases = sum(len(item["assessmentTestCases"]) for item in problems)
    practice = sum(len(item.get("practiceTestCases") or []) for item in problems)
    print("校验通过：%d 题，正式测试点 %d 个，练习测试点 %d 个，文件 %.1f KB" % (
        len(problems), cases, practice, len(raw) / 1024.0))
    return 0


if __name__ == "__main__":
    sys.exit(main())
