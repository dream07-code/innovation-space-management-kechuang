"""入门题（EASY）：标准输入输出、基础数学与线性结构。

每个题目定义包含：题面字段、参考解 solve(input) -> output、测试输入 inputs。
参考解只使用 Python 标准库的基础语法，便于与 Judge0 运行时对齐。
"""

import math


def _sum_two(text):
    a, b = map(int, text.split())
    return str(a + b)


def _even_odd(text):
    n = int(text.strip())
    return "even" if n % 2 == 0 else "odd"


def _gcd(text):
    a, b = map(int, text.split())
    return str(math.gcd(a, b))


def _is_prime(text):
    n = int(text.strip())
    if n < 2:
        return "no"
    if n % 2 == 0:
        return "yes" if n == 2 else "no"
    d = 3
    while d * d <= n:
        if n % d == 0:
            return "no"
        d += 2
    return "yes"


def _array_stats(text):
    data = text.split()
    n = int(data[0])
    nums = list(map(int, data[1 : 1 + n]))
    return "%d %d" % (sum(nums), max(nums))


def _reverse(text):
    return text.strip()[::-1]


def _palindrome(text):
    s = text.strip()
    return "yes" if s == s[::-1] else "no"


def _binary_search(text):
    data = text.split()
    n, q = int(data[0]), int(data[1])
    nums = list(map(int, data[2 : 2 + n]))
    queries = list(map(int, data[2 + n : 2 + n + q]))
    out = []
    for x in queries:
        lo, hi, pos = 0, n - 1, -1
        while lo <= hi:
            mid = (lo + hi) // 2
            if nums[mid] == x:
                pos = mid
                break
            if nums[mid] < x:
                lo = mid + 1
            else:
                hi = mid - 1
        out.append(str(pos))
    return "\n".join(out)


def _fib_mod(text):
    n = int(text.strip())
    mod = 1000000007
    if n <= 2:
        return "1"
    a, b = 1, 1
    for _ in range(3, n + 1):
        a, b = b, (a + b) % mod
    return str(b)


def _count_char(text):
    lines = text.split("\n")
    s = lines[0].strip()
    c = lines[1].strip()
    return str(s.count(c))


def _sort_three(text):
    nums = sorted(map(int, text.split()))
    return " ".join(map(str, nums))


def _trailing_zeros(text):
    n = int(text.strip())
    count = 0
    while n >= 5:
        n //= 5
        count += n
    return str(count)


PROBLEMS = [
    {
        "title": "A+B 问题",
        "summary": "读入两个整数，输出它们的和。",
        "description": (
            "给定两个整数 a 和 b，请计算并输出 a + b 的值。\n\n"
            "本题用于熟悉标准输入输出与整数运算。数据范围较大，请使用 64 位整数类型。"
        ),
        "inputFormat": "一行，包含两个用空格分隔的整数 a 和 b（-10^9 ≤ a, b ≤ 10^9）。",
        "outputFormat": "一行，一个整数，表示 a + b。",
        "examples": [{"input": "1 2", "output": "3", "explanation": "1 + 2 = 3。"}],
        "difficulty": "EASY",
        "tags": ["入门", "数学", "输入输出"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _sum_two,
        "inputs": [
            "1 2",
            "0 0",
            "-5 8",
            "1000000000 1000000000",
            "-1000000000 -1000000000",
            "123456789 -987654321",
            "42 -42",
        ],
    },
    {
        "title": "奇偶判定",
        "summary": "判断给定整数是偶数还是奇数。",
        "description": (
            "给定一个整数 n，请判断它是偶数还是奇数。\n\n"
            "若 n 能被 2 整除则输出 even，否则输出 odd。注意负数同样适用本规则。"
        ),
        "inputFormat": "一行，一个整数 n（-10^18 ≤ n ≤ 10^18）。",
        "outputFormat": "一行，even 或 odd。",
        "examples": [
            {"input": "4", "output": "even", "explanation": "4 能被 2 整除。"},
            {"input": "-7", "output": "odd", "explanation": "-7 不能被 2 整除。"},
        ],
        "difficulty": "EASY",
        "tags": ["入门", "数学", "条件判断"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _even_odd,
        "inputs": ["4", "-7", "0", "1", "999999999999999999", "-1000000000000000000", "2"],
    },
    {
        "title": "最大公约数",
        "summary": "求两个非负整数的最大公约数。",
        "description": (
            "给定两个非负整数 a 和 b，求它们的最大公约数（gcd）。\n\n"
            "约定 gcd(a, 0) = a，且 a、b 不会同时为 0。建议使用辗转相除法，避免逐个枚举。"
        ),
        "inputFormat": "一行，两个用空格分隔的整数 a 和 b（0 ≤ a, b ≤ 10^18）。",
        "outputFormat": "一行，一个整数，表示 a 和 b 的最大公约数。",
        "examples": [{"input": "12 18", "output": "6", "explanation": "12 和 18 的公约数中最大的是 6。"}],
        "difficulty": "EASY",
        "tags": ["入门", "数学", "数论"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _gcd,
        "inputs": [
            "12 18",
            "0 7",
            "7 0",
            "1 1",
            "1000000000000000000 999999999999999999",
            "270 192",
            "36 60",
        ],
    },
    {
        "title": "素数判定",
        "summary": "判断一个正整数是否为素数。",
        "description": (
            "给定一个正整数 n，判断它是否为素数。\n\n"
            "素数是指大于 1 且只能被 1 和自身整除的整数。1 不是素数。\n"
            "请将试除范围控制在 √n 以内，否则在较大数据上会超时。"
        ),
        "inputFormat": "一行，一个整数 n（1 ≤ n ≤ 10^7）。",
        "outputFormat": "一行，若 n 是素数输出 yes，否则输出 no。",
        "examples": [
            {"input": "7", "output": "yes", "explanation": "7 只能被 1 和 7 整除。"},
            {"input": "9", "output": "no", "explanation": "9 = 3 × 3。"},
        ],
        "difficulty": "EASY",
        "tags": ["入门", "数学", "数论", "素数"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _is_prime,
        "inputs": ["7", "9", "1", "2", "9999991", "10000000", "97", "1000003"],
    },
    {
        "title": "数组求和与最大值",
        "summary": "求一个整数序列的元素之和与最大值。",
        "description": (
            "给定 n 个整数，求它们的和以及最大值。\n\n"
            "请一次遍历完成统计，注意元素可能为负数，最大值初始化不能简单设为 0。"
        ),
        "inputFormat": "第一行一个整数 n（1 ≤ n ≤ 10^5）。\n第二行 n 个用空格分隔的整数 ai（-10^9 ≤ ai ≤ 10^9）。",
        "outputFormat": "一行，两个整数，依次为所有元素之和与最大值，用空格分隔。",
        "examples": [
            {"input": "5\n1 2 3 4 5", "output": "15 5", "explanation": "和为 15，最大值为 5。"},
            {"input": "3\n-5 -2 -9", "output": "-16 -2", "explanation": "全为负数时最大值为 -2。"},
        ],
        "difficulty": "EASY",
        "tags": ["入门", "数组", "遍历"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _array_stats,
        "inputs": [
            "5\n1 2 3 4 5",
            "3\n-5 -2 -9",
            "1\n0",
            "1\n-1000000000",
            "10\n1000000000 1000000000 1000000000 1000000000 1000000000 1000000000 1000000000 1000000000 1000000000 1000000000",
            "6\n5 -5 5 -5 5 -5",
        ],
    },
    {
        "title": "字符串反转",
        "summary": "将输入的字符串按字符顺序反转输出。",
        "description": (
            "给定一个只包含小写英文字母的字符串 s，请将其反转后输出。\n\n"
            "例如 abc 反转后为 cba。请注意字符串长度可能达到 10^5，应避免逐字符拼接带来的额外开销。"
        ),
        "inputFormat": "一行，一个由小写字母组成的字符串 s（1 ≤ |s| ≤ 10^5）。",
        "outputFormat": "一行，反转后的字符串。",
        "examples": [{"input": "abc", "output": "cba", "explanation": "字符顺序完全颠倒。"}],
        "difficulty": "EASY",
        "tags": ["入门", "字符串"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _reverse,
        "inputs": [
            "abc",
            "a",
            "abba",
            "abcdefghijklmnopqrstuvwxyz",
            "z" * 1000,
            "algorithm",
        ],
    },
    {
        "title": "回文串判定",
        "summary": "判断一个字符串是否为回文串。",
        "description": (
            "给定一个只包含小写字母的字符串 s，判断它是否为回文串。\n\n"
            "回文串是指正着读和反着读完全相同的字符串。空串不计入输入。"
        ),
        "inputFormat": "一行，一个由小写字母组成的字符串 s（1 ≤ |s| ≤ 10^5）。",
        "outputFormat": "一行，若是回文串输出 yes，否则输出 no。",
        "examples": [
            {"input": "level", "output": "yes", "explanation": "正反读都是 level。"},
            {"input": "algorithm", "output": "no", "explanation": "反转后与原串不同。"},
        ],
        "difficulty": "EASY",
        "tags": ["入门", "字符串", "双指针"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _palindrome,
        "inputs": ["level", "algorithm", "a", "ab", "abba", "abcba", "a" * 1001, "abccba"],
    },
    {
        "title": "二分查找",
        "summary": "在升序数组中查找若干目标值的下标。",
        "description": (
            "给定一个长度为 n 的升序整数数组，以及 q 次查询。\n\n"
            "对每次查询的目标值 x，输出 x 在数组中的下标（下标从 0 开始）。\n"
            "若数组中存在多个等于 x 的元素，输出其中任意一个即可；若不存在，输出 -1。\n"
            "数组元素可能重复，请保证每次查询的时间复杂度为 O(log n)。"
        ),
        "inputFormat": (
            "第一行两个整数 n 和 q（1 ≤ n, q ≤ 10^5）。\n"
            "第二行 n 个升序排列的整数 ai（-10^9 ≤ ai ≤ 10^9）。\n"
            "第三行 q 个整数，表示每次查询的 x（-10^9 ≤ x ≤ 10^9）。"
        ),
        "outputFormat": "共 q 行，每行一个整数，表示查询结果的下标或 -1。",
        "examples": [
            {
                "input": "5 3\n1 3 5 7 9\n3 8 9",
                "output": "1\n-1\n4",
                "explanation": "3 在下标 1，8 不存在，9 在下标 4。",
            }
        ],
        "difficulty": "EASY",
        "tags": ["入门", "二分查找", "数组"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _binary_search,
        "inputs": [
            "5 3\n1 3 5 7 9\n3 8 9",
            "1 2\n42\n42 -1",
            "6 4\n1 1 2 2 3 3\n1 2 3 4",
            "10 5\n-9 -7 -5 -3 -1 1 3 5 7 9\n-9 9 0 -1 7",
            "4 3\n0 0 0 0\n0 1 -1",
        ],
    },
    {
        "title": "斐波那契数列",
        "summary": "求斐波那契数列第 n 项对 10^9+7 取模的结果。",
        "description": (
            "斐波那契数列定义如下：F(1) = 1，F(2) = 1，当 n ≥ 3 时 F(n) = F(n-1) + F(n-2)。\n\n"
            "给定 n，求 F(n) mod (10^9+7)。由于结果可能很大，请每步取模。"
        ),
        "inputFormat": "一行，一个整数 n（1 ≤ n ≤ 10^6）。",
        "outputFormat": "一行，一个整数，表示 F(n) mod 1000000007。",
        "examples": [
            {"input": "1", "output": "1", "explanation": "F(1) = 1。"},
            {"input": "6", "output": "8", "explanation": "数列为 1, 1, 2, 3, 5, 8。"},
        ],
        "difficulty": "EASY",
        "tags": ["入门", "递推", "动态规划"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _fib_mod,
        "inputs": ["1", "2", "3", "6", "10", "1000", "1000000", "999999"],
    },
    {
        "title": "统计字符出现次数",
        "summary": "统计指定字符在字符串中出现的次数。",
        "description": (
            "给定一个只包含小写字母的字符串 s 和一个目标字符 c，统计 c 在 s 中出现的次数。\n\n"
            "请按字符逐位比较，注意区分大小写。"
        ),
        "inputFormat": "第一行一个字符串 s（1 ≤ |s| ≤ 10^5）。\n第二行一个字符 c。",
        "outputFormat": "一行，一个整数，表示 c 在 s 中出现的次数。",
        "examples": [{"input": "banana\na", "output": "3", "explanation": "banana 中有 3 个 a。"}],
        "difficulty": "EASY",
        "tags": ["入门", "字符串", "计数"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _count_char,
        "inputs": [
            "banana\na",
            "abc\nz",
            "a\na",
            "mississippi\ns",
            "zzzzzzzzzz\nz",
            "helloworld\nl",
        ],
    },
    {
        "title": "三个数排序",
        "summary": "将三个整数按从小到大输出。",
        "description": (
            "给定三个整数 a、b、c，请将它们按从小到大的顺序输出。\n\n"
            "数值可能相同，也可能为负数。"
        ),
        "inputFormat": "一行，三个用空格分隔的整数 a、b、c（-10^9 ≤ a, b, c ≤ 10^9）。",
        "outputFormat": "一行，三个整数，按从小到大排列，用空格分隔。",
        "examples": [{"input": "3 1 2", "output": "1 2 3", "explanation": "升序排列后为 1、2、3。"}],
        "difficulty": "EASY",
        "tags": ["入门", "排序", "条件判断"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _sort_three,
        "inputs": ["3 1 2", "5 5 5", "-1 0 1", "1000000000 -1000000000 0", "9 8 7", "1 1 0"],
    },
    {
        "title": "阶乘末尾零的个数",
        "summary": "求 n! 的十进制表示末尾连续零的个数。",
        "description": (
            "给定非负整数 n，求 n!（n 的阶乘）的十进制表示末尾有多少个连续的 0。\n\n"
            "末尾零由因子 10 产生，而 10 = 2 × 5，因子 2 的数量远多于 5，因此只需统计因子 5 的个数：\n"
            "answer = ⌊n/5⌋ + ⌊n/25⌋ + ⌊n/125⌋ + …\n"
            "n 可达 10^9，不能直接计算阶乘。"
        ),
        "inputFormat": "一行，一个整数 n（0 ≤ n ≤ 10^9）。",
        "outputFormat": "一行，一个整数，表示 n! 末尾连续零的个数。",
        "examples": [
            {"input": "10", "output": "2", "explanation": "10! = 3628800，末尾有 2 个零。"},
            {"input": "25", "output": "6", "explanation": "25 贡献 5 和 25 两个 5 的倍数，共 6 个因子 5。"},
        ],
        "difficulty": "EASY",
        "tags": ["入门", "数学", "数论"],
        "timeLimitMs": 1000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _trailing_zeros,
        "inputs": ["0", "4", "5", "10", "25", "100", "1000000000", "125"],
    },
]
