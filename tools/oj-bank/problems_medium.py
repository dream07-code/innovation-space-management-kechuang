"""进阶题（MEDIUM）：双指针、哈希、栈、贪心、动态规划、图与并查集。

随机测试数据使用固定种子生成，保证每次产出的 JSON 完全一致。
"""

import heapq
import random


def _max_subarray(text):
    data = text.split()
    n = int(data[0])
    nums = list(map(int, data[1 : 1 + n]))
    best = cur = nums[0]
    for x in nums[1:]:
        cur = max(x, cur + x)
        best = max(best, cur)
    return str(best)


def _merge_intervals(text):
    data = text.split()
    n = int(data[0])
    pairs = []
    for i in range(n):
        pairs.append((int(data[1 + 2 * i]), int(data[2 + 2 * i])))
    pairs.sort()
    merged = []
    for left, right in pairs:
        if merged and left <= merged[-1][1]:
            merged[-1][1] = max(merged[-1][1], right)
        else:
            merged.append([left, right])
    lines = [str(len(merged))]
    for left, right in merged:
        lines.append("%d %d" % (left, right))
    return "\n".join(lines)


def _brackets(text):
    s = text.strip()
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    for ch in s:
        if ch in "([{":
            stack.append(ch)
        elif ch in pairs:
            if not stack or stack[-1] != pairs[ch]:
                return "no"
            stack.pop()
    return "yes" if not stack else "no"


def _two_sum(text):
    data = text.split()
    n, target = int(data[0]), int(data[1])
    nums = list(map(int, data[2 : 2 + n]))
    seen = {}
    for j, value in enumerate(nums):
        need = target - value
        if need in seen:
            return "%d %d" % (seen[need], j)
        if value not in seen:
            seen[value] = j
    return "-1"


def _power_mod(text):
    a, b, p = map(int, text.split())
    return str(pow(a, b, p))


def _count_primes(text):
    n = int(text.strip())
    if n < 2:
        return "0"
    sieve = bytearray([1]) * (n + 1)
    sieve[0:2] = b"\x00\x00"
    i = 2
    while i * i <= n:
        if sieve[i]:
            sieve[i * i : n + 1 : i] = b"\x00" * (((n - i * i) // i) + 1)
        i += 1
    return str(sum(sieve))


def _knapsack(text):
    data = text.split()
    n, cap = int(data[0]), int(data[1])
    weights = []
    values = []
    for i in range(n):
        weights.append(int(data[2 + 2 * i]))
        values.append(int(data[3 + 2 * i]))
    dp = [0] * (cap + 1)
    for i in range(n):
        for c in range(cap, weights[i] - 1, -1):
            candidate = dp[c - weights[i]] + values[i]
            if candidate > dp[c]:
                dp[c] = candidate
    return str(dp[cap])


def _lis(text):
    data = text.split()
    n = int(data[0])
    nums = list(map(int, data[1 : 1 + n]))
    tails = []
    for x in nums:
        lo, hi = 0, len(tails)
        while lo < hi:
            mid = (lo + hi) // 2
            if tails[mid] < x:
                lo = mid + 1
            else:
                hi = mid
        if lo == len(tails):
            tails.append(x)
        else:
            tails[lo] = x
    return str(len(tails))


def _islands(text):
    lines = [line for line in text.strip().split("\n") if line.strip()]
    rows, cols = map(int, lines[0].split())
    grid = [list(lines[1 + i].strip()) for i in range(rows)]
    count = 0
    for i in range(rows):
        for j in range(cols):
            if grid[i][j] != "1":
                continue
            count += 1
            stack = [(i, j)]
            grid[i][j] = "0"
            while stack:
                x, y = stack.pop()
                for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    nx, ny = x + dx, y + dy
                    if 0 <= nx < rows and 0 <= ny < cols and grid[nx][ny] == "1":
                        grid[nx][ny] = "0"
                        stack.append((nx, ny))
    return str(count)


def _dijkstra(text):
    data = text.split()
    n, m = int(data[0]), int(data[1])
    graph = [[] for _ in range(n + 1)]
    idx = 2
    for _ in range(m):
        u, v, w = int(data[idx]), int(data[idx + 1]), int(data[idx + 2])
        idx += 3
        graph[u].append((v, w))
    inf = float("inf")
    dist = [inf] * (n + 1)
    dist[1] = 0
    heap = [(0, 1)]
    while heap:
        d, u = heapq.heappop(heap)
        if d > dist[u]:
            continue
        for v, w in graph[u]:
            nd = d + w
            if nd < dist[v]:
                dist[v] = nd
                heapq.heappush(heap, (nd, v))
    return "-1" if dist[n] == inf else str(dist[n])


def _components(text):
    data = text.split()
    n, m = int(data[0]), int(data[1])
    parent = list(range(n + 1))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    for i in range(m):
        a, b = int(data[2 + 2 * i]), int(data[3 + 2 * i])
        ra, rb = find(a), find(b)
        if ra != rb:
            parent[ra] = rb
    return str(len({find(i) for i in range(1, n + 1)}))


def _edit_distance(text):
    lines = text.strip().split("\n")
    a, b = lines[0].strip(), lines[1].strip()
    prev = list(range(len(b) + 1))
    for i in range(1, len(a) + 1):
        cur = [i] + [0] * len(b)
        for j in range(1, len(b) + 1):
            cost = 0 if a[i - 1] == b[j - 1] else 1
            cur[j] = min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
        prev = cur
    return str(prev[len(b)])


def _rand_ints(rng, n, low, high):
    return [rng.randint(low, high) for _ in range(n)]


def _build_random_cases():
    rng = random.Random(20261021)
    cases = {}

    sub_arrays = []
    for n, low, high in ((10, -10, 10), (50, -100, 100), (200, -1000, 1000), (1000, -10000, 10000)):
        sub_arrays.append(str(n) + "\n" + " ".join(map(str, _rand_ints(rng, n, low, high))))
    cases["max_subarray"] = sub_arrays

    intervals = []
    for n in (1, 5, 30, 200):
        items = []
        for _ in range(n):
            left = rng.randint(0, 1000)
            items.append("%d %d" % (left, left + rng.randint(0, 100)))
        intervals.append(str(n) + "\n" + "\n".join(items))
    cases["intervals"] = intervals

    knaps = []
    for n, cap in ((3, 10), (10, 50), (50, 200), (100, 1000)):
        rows = []
        for _ in range(n):
            rows.append("%d %d" % (rng.randint(1, cap), rng.randint(1, 100)))
        knaps.append("%d %d\n" % (n, cap) + "\n".join(rows))
    cases["knapsack"] = knaps

    lis_cases = []
    for n, low, high in ((5, 1, 10), (20, 1, 50), (200, 1, 1000), (1000, 1, 100000)):
        lis_cases.append(str(n) + "\n" + " ".join(map(str, _rand_ints(rng, n, low, high))))
    cases["lis"] = lis_cases

    graphs = []
    for n, m, wmax in ((4, 5, 10), (10, 30, 100), (100, 400, 1000), (1000, 5000, 10000)):
        rows = []
        for _ in range(m):
            rows.append("%d %d %d" % (rng.randint(1, n), rng.randint(1, n), rng.randint(1, wmax)))
        graphs.append("%d %d\n" % (n, m) + "\n".join(rows))
    cases["dijkstra"] = graphs

    comps = []
    for n, m in ((1, 0), (5, 2), (100, 50), (1000, 400)):
        rows = []
        for _ in range(m):
            rows.append("%d %d" % (rng.randint(1, n), rng.randint(1, n)))
        comps.append("%d %d\n" % (n, m) + "\n".join(rows))
    cases["components"] = comps

    grids = []
    for rows, cols in ((1, 1), (3, 4), (10, 10), (50, 50)):
        lines = ["%d %d" % (rows, cols)]
        for _ in range(rows):
            lines.append("".join(rng.choice("0011") for _ in range(cols)))
        grids.append("\n".join(lines))
    cases["islands"] = grids

    return cases


_RANDOM = _build_random_cases()


PROBLEMS = [
    {
        "title": "最大子段和",
        "summary": "求一个整数序列中连续子段的最大和。",
        "description": (
            "给定一个长度为 n 的整数序列，请找出一个连续子段，使该子段内所有元素之和最大，输出这个最大和。\n\n"
            "子段必须非空。序列中可能全部为负数，此时答案就是最大的那个负数。\n"
            "请使用 O(n) 的算法，例如动态规划（Kadane 算法）。"
        ),
        "inputFormat": "第一行一个整数 n（1 ≤ n ≤ 10^5）。\n第二行 n 个用空格分隔的整数 ai（-10^4 ≤ ai ≤ 10^4）。",
        "outputFormat": "一行，一个整数，表示最大连续子段和。",
        "examples": [
            {
                "input": "8\n-2 1 -3 4 -1 2 1 -5",
                "output": "6",
                "explanation": "子段 [4, -1, 2, 1] 的和为 6，是最大连续子段和。",
            }
        ],
        "difficulty": "MEDIUM",
        "tags": ["动态规划", "数组", "贪心"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _max_subarray,
        "inputs": [
            "8\n-2 1 -3 4 -1 2 1 -5",
            "1\n-7",
            "5\n-3 -1 -9 -2 -6",
            "6\n1 2 3 4 5 6",
            "4\n0 0 0 0",
        ]
        + _RANDOM["max_subarray"],
    },
    {
        "title": "区间合并",
        "summary": "合并所有重叠的闭区间并按左端点排序输出。",
        "description": (
            "给定 n 个闭区间 [li, ri]，请合并所有互相重叠或有公共端点的区间，并按左端点从小到大输出合并后的区间。\n\n"
            "若两个区间满足 li ≤ rj 且 lj ≤ ri，则它们重叠。合并后的区间数量至少为 1。"
        ),
        "inputFormat": "第一行一个整数 n（1 ≤ n ≤ 10^5）。\n接下来 n 行，每行两个整数 li 和 ri（0 ≤ li ≤ ri ≤ 10^9）。",
        "outputFormat": "第一行一个整数 k，表示合并后的区间数量。\n接下来 k 行，每行两个整数，表示一个合并后的闭区间，按左端点升序排列。",
        "examples": [
            {
                "input": "4\n1 3\n2 6\n8 10\n15 18",
                "output": "3\n1 6\n8 10\n15 18",
                "explanation": "[1,3] 与 [2,6] 重叠，合并为 [1,6]，其余区间互不重叠。",
            }
        ],
        "difficulty": "MEDIUM",
        "tags": ["排序", "贪心", "区间"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _merge_intervals,
        "inputs": [
            "4\n1 3\n2 6\n8 10\n15 18",
            "1\n0 0",
            "3\n1 2\n2 3\n3 4",
            "3\n5 6\n1 2\n3 4",
            "2\n1 10\n2 3",
        ]
        + _RANDOM["intervals"],
    },
    {
        "title": "括号匹配",
        "summary": "判断由三种括号组成的字符串是否合法匹配。",
        "description": (
            "给定一个只包含 ()[]{} 六种字符的字符串 s，判断其中的括号是否合法匹配。\n\n"
            "合法匹配要求：每个左括号都有对应的右括号，且嵌套顺序正确。例如 ()[]、{[()]} 合法，"
            "(] 与 ([)] 不合法。建议使用栈实现。"
        ),
        "inputFormat": "一行，一个由 ()[]{} 组成的字符串 s（1 ≤ |s| ≤ 10^5）。",
        "outputFormat": "一行，合法输出 yes，否则输出 no。",
        "examples": [
            {"input": "{[()]}", "output": "yes", "explanation": "嵌套顺序完全正确。"},
            {"input": "([)]", "output": "no", "explanation": "圆括号与方括号交叉嵌套，不合法。"},
        ],
        "difficulty": "MEDIUM",
        "tags": ["栈", "字符串", "括号匹配"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _brackets,
        "inputs": [
            "{[()]}",
            "([)]",
            "()[]{}",
            "((",
            ")(",
            "(((((((((())))))))))",
            "[]{}()",
        ],
    },
    {
        "title": "两数之和",
        "summary": "在数组中找出一对下标，使其对应元素之和等于目标值。",
        "description": (
            "给定长度为 n 的整数数组和目标值 target，请找出一对下标 i 和 j（0 ≤ i < j < n），"
            "使得 a[i] + a[j] = target。\n\n"
            "若存在多组解，输出 i 最小的那一组；若 i 相同仍有多个，输出 j 最小的那一组。\n"
            "若不存在这样的下标对，输出 -1。请使用哈希表将复杂度控制在 O(n)。"
        ),
        "inputFormat": "第一行两个整数 n 和 target（2 ≤ n ≤ 10^5，-10^9 ≤ target ≤ 10^9）。\n第二行 n 个整数 ai（-10^9 ≤ ai ≤ 10^9）。",
        "outputFormat": "一行。若存在解，输出两个整数 i 和 j（用空格分隔）；否则输出 -1。",
        "examples": [
            {
                "input": "4 9\n2 7 11 15",
                "output": "0 1",
                "explanation": "a[0] + a[1] = 2 + 7 = 9。",
            },
            {"input": "3 100\n1 2 3", "output": "-1", "explanation": "任意两数之和都不等于 100。"},
        ],
        "difficulty": "MEDIUM",
        "tags": ["哈希表", "数组", "查找"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _two_sum,
        "inputs": [
            "4 9\n2 7 11 15",
            "3 100\n1 2 3",
            "5 6\n3 3 4 2 1",
            "2 0\n0 0",
            "6 8\n1 5 3 7 9 2",
            "4 -2\n-1 -1 -3 0",
        ],
    },
    {
        "title": "快速幂取模",
        "summary": "计算 a 的 b 次方对 p 取模的结果。",
        "description": (
            "给定三个整数 a、b、p，计算 a^b mod p。\n\n"
            "b 可能达到 10^18，直接循环会超时，请使用快速幂（二进制幂）。\n"
            "当 p = 1 时，结果恒为 0。"
        ),
        "inputFormat": "一行，三个整数 a、b、p（0 ≤ a ≤ 10^9，0 ≤ b ≤ 10^18，1 ≤ p ≤ 10^9）。",
        "outputFormat": "一行，一个整数，表示 a^b mod p。",
        "examples": [
            {"input": "2 10 1000", "output": "24", "explanation": "2^10 = 1024，1024 mod 1000 = 24。"},
            {"input": "3 0 7", "output": "1", "explanation": "任何非零数的 0 次方为 1，1 mod 7 = 1。"},
        ],
        "difficulty": "MEDIUM",
        "tags": ["数学", "快速幂", "位运算"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _power_mod,
        "inputs": [
            "2 10 1000",
            "3 0 7",
            "0 5 13",
            "5 1 1",
            "123456789 1000000000000000000 1000000007",
            "7 13 97",
        ],
    },
    {
        "title": "统计素数个数",
        "summary": "统计不超过 n 的素数个数。",
        "description": (
            "给定正整数 n，统计区间 [1, n] 内素数的个数。\n\n"
            "逐个试除在 n 较大时无法通过，请使用埃拉托斯特尼筛法或其他线性筛法。"
        ),
        "inputFormat": "一行，一个整数 n（1 ≤ n ≤ 10^6）。",
        "outputFormat": "一行，一个整数，表示不超过 n 的素数个数。",
        "examples": [
            {"input": "10", "output": "4", "explanation": "不超过 10 的素数为 2、3、5、7。"},
            {"input": "1", "output": "0", "explanation": "1 不是素数。"},
        ],
        "difficulty": "MEDIUM",
        "tags": ["数论", "筛法", "素数"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _count_primes,
        "inputs": ["10", "1", "2", "100", "10000", "1000000", "999983"],
    },
    {
        "title": "0-1 背包",
        "summary": "在容量限制下选择物品，使总价值最大。",
        "description": (
            "有 n 件物品，第 i 件物品的重量为 wi、价值为 vi。背包的最大承重为 W，"
            "每件物品最多只能选一次（选或不选）。\n\n"
            "求在总重量不超过 W 的前提下，能够获得的最大总价值。\n"
            "请使用一维数组实现 0-1 背包，并注意容量必须倒序遍历。"
        ),
        "inputFormat": "第一行两个整数 n 和 W（1 ≤ n ≤ 100，1 ≤ W ≤ 1000）。\n接下来 n 行，每行两个整数 wi 和 vi（1 ≤ wi ≤ W，1 ≤ vi ≤ 100）。",
        "outputFormat": "一行，一个整数，表示最大总价值。",
        "examples": [
            {
                "input": "3 10\n5 10\n4 40\n6 30",
                "output": "70",
                "explanation": "选择重量 4、价值 40 与重量 6、价值 30 的物品，总重量恰好 10，总价值 70。",
            }
        ],
        "difficulty": "MEDIUM",
        "tags": ["动态规划", "背包"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _knapsack,
        "inputs": [
            "3 10\n5 10\n4 40\n6 30",
            "1 5\n6 100",
            "1 5\n5 100",
            "3 5\n1 10\n2 20\n3 30",
            "4 10\n10 1\n10 2\n10 3\n10 4",
            "3 10\n6 6\n5 5\n5 5",
        ]
        + _RANDOM["knapsack"],
    },
    {
        "title": "最长上升子序列",
        "summary": "求序列的最长严格上升子序列长度。",
        "description": (
            "给定一个长度为 n 的整数序列，求其最长严格上升子序列（LIS）的长度。\n\n"
            "子序列不要求连续，但必须保持原有相对顺序，且后一个元素严格大于前一个元素。\n"
            "例如序列 10 9 2 5 3 7 101 18 的 LIS 为 2 3 7 101，长度为 4。"
        ),
        "inputFormat": "第一行一个整数 n（1 ≤ n ≤ 1000）。\n第二行 n 个整数 ai（-10^9 ≤ ai ≤ 10^9）。",
        "outputFormat": "一行，一个整数，表示最长严格上升子序列的长度。",
        "examples": [
            {
                "input": "8\n10 9 2 5 3 7 101 18",
                "output": "4",
                "explanation": "2 3 7 101 是最长的严格上升子序列之一。",
            }
        ],
        "difficulty": "MEDIUM",
        "tags": ["动态规划", "二分查找", "序列"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _lis,
        "inputs": ["8\n10 9 2 5 3 7 101 18", "1\n5", "5\n5 4 3 2 1", "4\n1 1 1 1", "5\n1 2 3 4 5"]
        + _RANDOM["lis"],
    },
    {
        "title": "岛屿数量",
        "summary": "统计 0-1 网格中四连通陆地形成的岛屿数量。",
        "description": (
            "给定一个由 0 和 1 组成的网格，1 表示陆地，0 表示水域。\n\n"
            "相邻（上下左右四个方向）的陆地视为同一座岛屿，请统计岛屿的数量。\n"
            "网格四周默认被水域包围。"
        ),
        "inputFormat": "第一行两个整数 rows 和 cols（1 ≤ rows, cols ≤ 100）。\n接下来 rows 行，每行一个长度为 cols 的 0-1 字符串。",
        "outputFormat": "一行，一个整数，表示岛屿数量。",
        "examples": [
            {
                "input": "4 5\n11000\n11000\n00100\n00011",
                "output": "3",
                "explanation": "左上角一块、中间一块、右下角一块，共 3 座岛屿。",
            }
        ],
        "difficulty": "MEDIUM",
        "tags": ["图论", "深度优先搜索", "广度优先搜索", "网格"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _islands,
        "inputs": [
            "4 5\n11000\n11000\n00100\n00011",
            "1 1\n0",
            "1 1\n1",
            "3 3\n111\n111\n111",
            "3 3\n101\n010\n101",
        ]
        + _RANDOM["islands"],
    },
    {
        "title": "单源最短路径",
        "summary": "求有向带权图中 1 号点到 n 号点的最短距离。",
        "description": (
            "给定一个 n 个点、m 条边的有向图，边权均为正整数，可能存在重边和自环。\n\n"
            "求从 1 号点出发到 n 号点的最短路径长度。若无法到达，输出 -1。\n"
            "请使用堆优化的 Dijkstra 算法，复杂度 O((n+m) log n)。"
        ),
        "inputFormat": (
            "第一行两个整数 n 和 m（1 ≤ n ≤ 1000，0 ≤ m ≤ 5000）。\n"
            "接下来 m 行，每行三个整数 u、v、w，表示一条从 u 到 v、长度为 w 的有向边（1 ≤ u, v ≤ n，1 ≤ w ≤ 10^4）。"
        ),
        "outputFormat": "一行，一个整数，表示 1 到 n 的最短距离；不可达输出 -1。",
        "examples": [
            {
                "input": "4 4\n1 2 1\n2 3 2\n3 4 3\n1 4 100",
                "output": "6",
                "explanation": "1→2→3→4 的总长度为 6，优于直接边 100。",
            }
        ],
        "difficulty": "MEDIUM",
        "tags": ["图论", "最短路", "堆", "Dijkstra"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _dijkstra,
        "inputs": [
            "4 4\n1 2 1\n2 3 2\n3 4 3\n1 4 100",
            "1 0",
            "2 0",
            "3 2\n1 2 5\n2 3 5",
            "3 3\n1 1 1\n1 2 2\n2 1 2",
        ]
        + _RANDOM["dijkstra"],
    },
    {
        "title": "连通块数量",
        "summary": "用并查集统计无向图的连通块个数。",
        "description": (
            "给定一个包含 n 个点、m 条边的无向图（可能存在重边和自环），求图中连通块的数量。\n\n"
            "孤立点自身也算一个连通块。请使用并查集（Union-Find）实现，并配合路径压缩。"
        ),
        "inputFormat": "第一行两个整数 n 和 m（1 ≤ n ≤ 1000，0 ≤ m ≤ 5000）。\n接下来 m 行，每行两个整数 u、v，表示 u 与 v 之间有一条无向边（1 ≤ u, v ≤ n）。",
        "outputFormat": "一行，一个整数，表示连通块数量。",
        "examples": [
            {
                "input": "5 3\n1 2\n2 3\n4 5",
                "output": "2",
                "explanation": "{1,2,3} 与 {4,5} 两个连通块。",
            }
        ],
        "difficulty": "MEDIUM",
        "tags": ["图论", "并查集", "连通性"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _components,
        "inputs": ["5 3\n1 2\n2 3\n4 5", "1 0", "3 3\n1 2\n2 3\n1 3", "4 4\n1 1\n2 2\n3 3\n4 4"]
        + _RANDOM["components"],
    },
    {
        "title": "编辑距离",
        "summary": "求两个字符串之间的最小编辑距离。",
        "description": (
            "给定两个字符串 a 和 b，求将 a 变成 b 所需的最少操作次数。\n\n"
            "允许的操作有三种：插入一个字符、删除一个字符、替换一个字符，每次操作的代价均为 1。\n"
            "例如将 kitten 变为 sitting 需要 3 次操作。请使用动态规划，dp[i][j] 表示 a 的前 i 个字符"
            "与 b 的前 j 个字符的编辑距离。"
        ),
        "inputFormat": "第一行一个字符串 a，第二行一个字符串 b（1 ≤ |a|, |b| ≤ 500，仅含小写字母）。",
        "outputFormat": "一行，一个整数，表示最小编辑距离。",
        "examples": [
            {"input": "kitten\nsitting", "output": "3", "explanation": "k→s，e→i，末尾插入 g。"},
            {"input": "abc\nabc", "output": "0", "explanation": "两个字符串相同，无需操作。"},
        ],
        "difficulty": "MEDIUM",
        "tags": ["动态规划", "字符串", "编辑距离"],
        "timeLimitMs": 2000,
        "memoryLimitKb": 262144,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _edit_distance,
        "inputs": [
            "kitten\nsitting",
            "abc\nabc",
            "a\nb",
            "algorithm\nlogarithm",
            "intention\nexecution",
            "abcdefghij\njihgfedcba",
        ],
    },
]
