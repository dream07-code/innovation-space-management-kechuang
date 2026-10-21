#!/usr/bin/env python3
"""弱解检测：用常见错误算法跑一遍题库，确认测试数据真的能判错。

如果某个错误解在所有测试点上都通过，说明该题的测试数据区分度不足，
需要补充边界或更大规模的数据。

用法：
    python3 check_weak_solutions.py out/oj-problems-30.json
"""

import json
import sys


def trailing_zeros_wrong(text):
    """只统计 n/5，漏掉 25、125 等高次幂因子。"""
    return str(int(text.strip()) // 5)


def prime_wrong(text):
    """把 1 当成素数。"""
    n = int(text.strip())
    if n <= 1:
        return "yes"
    d = 2
    while d * d <= n:
        if n % d == 0:
            return "no"
        d += 1
    return "yes"


def max_subarray_wrong(text):
    """答案初始化为 0，全负数序列会返回 0。"""
    data = text.split()
    n = int(data[0])
    nums = list(map(int, data[1 : 1 + n]))
    best = 0
    cur = 0
    for x in nums:
        cur = max(0, cur + x)
        best = max(best, cur)
    return str(best)


def fib_wrong(text):
    """不取模，数值会溢出（Python 大整数则结果数值不匹配）。"""
    n = int(text.strip())
    if n <= 2:
        return "1"
    a, b = 1, 1
    for _ in range(3, n + 1):
        a, b = b, a + b
    return str(b)


def two_sum_wrong(text):
    """返回最后一组解，而不是 i 最小的解。"""
    data = text.split()
    n, target = int(data[0]), int(data[1])
    nums = list(map(int, data[2 : 2 + n]))
    answer = "-1"
    for i in range(n):
        for j in range(i + 1, n):
            if nums[i] + nums[j] == target:
                answer = "%d %d" % (i, j)
    return answer


def knapsack_wrong(text):
    """按单位价值贪心，不保证最优。"""
    data = text.split()
    n, cap = int(data[0]), int(data[1])
    items = []
    for i in range(n):
        w, v = int(data[2 + 2 * i]), int(data[3 + 2 * i])
        items.append((v / w, w, v))
    items.sort(reverse=True)
    total = 0
    for _, w, v in items:
        if cap >= w:
            cap -= w
            total += v
    return str(total)


def lis_wrong(text):
    """求最长连续上升段，而不是子序列。"""
    data = text.split()
    n = int(data[0])
    nums = list(map(int, data[1 : 1 + n]))
    best = cur = 1
    for i in range(1, n):
        cur = cur + 1 if nums[i] > nums[i - 1] else 1
        best = max(best, cur)
    return str(best)


def sliding_wrong(text):
    """窗口左端点用 i-k+1 计算错误，导致首窗口偏移。"""
    data = text.split()
    n, k = int(data[0]), int(data[1])
    nums = list(map(int, data[2 : 2 + n]))
    out = []
    for i in range(k - 1, n):
        out.append(str(max(nums[i - k + 1 : i + 1])))
    return " ".join(out[:-1]) if out else ""


WEAK = {
    "阶乘末尾零的个数": trailing_zeros_wrong,
    "素数判定": prime_wrong,
    "最大子段和": max_subarray_wrong,
    "斐波那契数列": fib_wrong,
    "两数之和": two_sum_wrong,
    "0-1 背包": knapsack_wrong,
    "最长上升子序列": lis_wrong,
    "滑动窗口最大值": sliding_wrong,
}


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "out/oj-problems-30.json"
    with open(path, "r", encoding="utf-8") as handle:
        problems = json.load(handle)["problems"]

    by_title = {item["title"]: item for item in problems}
    failures = 0

    for title, wrong in WEAK.items():
        problem = by_title.get(title)
        if problem is None:
            print("跳过（题库中没有此题）：%s" % title)
            continue
        cases = problem["assessmentTestCases"]
        rejected = 0
        first_bad = None
        for index, case in enumerate(cases):
            try:
                produced = wrong(case["input"]).strip()
            except Exception:  # 错误解崩溃同样算被拒
                rejected += 1
                if first_bad is None:
                    first_bad = index
                continue
            if produced != case["expectedOutput"].strip():
                rejected += 1
                if first_bad is None:
                    first_bad = index
        if rejected == 0:
            failures += 1
            print("[不足] %-24s 错误解通过了全部 %d 个测试点" % (title, len(cases)))
        else:
            print("[OK]   %-24s %d/%d 个测试点判错（首个失败点 #%d）" % (
                title, rejected, len(cases), first_bad + 1))

    if failures:
        print("\n有 %d 道题的测试数据区分度不足，建议补充边界数据。" % failures)
        return 1
    print("\n全部被检测的错误解都能被测试数据拒绝。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
