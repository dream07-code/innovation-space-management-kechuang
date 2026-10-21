"""高阶题（HARD）：拓扑排序、线段树、矩阵快速幂、最小生成树与单调队列。"""

import heapq
import random
from collections import deque


def _topo_sort(text):
    data = text.split()
    n, m = int(data[0]), int(data[1])
    graph = [[] for _ in range(n + 1)]
    indeg = [0] * (n + 1)
    for i in range(m):
        u, v = int(data[2 + 2 * i]), int(data[3 + 2 * i])
        graph[u].append(v)
        indeg[v] += 1
    heap = [i for i in range(1, n + 1) if indeg[i] == 0]
    heapq.heapify(heap)
    order = []
    while heap:
        u = heapq.heappop(heap)
        order.append(u)
        for v in graph[u]:
            indeg[v] -= 1
            if indeg[v] == 0:
                heapq.heappush(heap, v)
    if len(order) != n:
        return "-1"
    return " ".join(map(str, order))


def _segment_tree(text):
    lines = [line for line in text.strip().split("\n") if line.strip()]
    n, q = map(int, lines[0].split())
    nums = list(map(int, lines[1].split()))
    size = 1
    while size < n:
        size <<= 1
    tree = [0] * (2 * size)
    for i in range(n):
        tree[size + i] = nums[i]
    for i in range(size - 1, 0, -1):
        tree[i] = tree[2 * i] + tree[2 * i + 1]

    def update(pos, value):
        idx = size + pos
        tree[idx] = value
        idx //= 2
        while idx:
            tree[idx] = tree[2 * idx] + tree[2 * idx + 1]
            idx //= 2

    def query(left, right):
        left += size
        right += size
        total = 0
        while left <= right:
            if left % 2 == 1:
                total += tree[left]
                left += 1
            if right % 2 == 0:
                total += tree[right]
                right -= 1
            left //= 2
            right //= 2
        return total

    out = []
    for line in lines[2 : 2 + q]:
        parts = list(map(int, line.split()))
        if parts[0] == 1:
            out.append(str(query(parts[1] - 1, parts[2] - 1)))
        else:
            update(parts[1] - 1, parts[2])
    return "\n".join(out)


def _lcs(text):
    lines = text.strip().split("\n")
    a, b = lines[0].strip(), lines[1].strip()
    prev = [0] * (len(b) + 1)
    for i in range(1, len(a) + 1):
        cur = [0] * (len(b) + 1)
        for j in range(1, len(b) + 1):
            if a[i - 1] == b[j - 1]:
                cur[j] = prev[j - 1] + 1
            else:
                cur[j] = max(prev[j], cur[j - 1])
        prev = cur
    return str(prev[len(b)])


def _mat_mul(x, y, mod):
    return [
        [(x[0][0] * y[0][0] + x[0][1] * y[1][0]) % mod, (x[0][0] * y[0][1] + x[0][1] * y[1][1]) % mod],
        [(x[1][0] * y[0][0] + x[1][1] * y[1][0]) % mod, (x[1][0] * y[0][1] + x[1][1] * y[1][1]) % mod],
    ]


def _fib_fast(text):
    n = int(text.strip())
    mod = 1000000007
    if n <= 2:
        return "1"
    result = [[1, 0], [0, 1]]
    base = [[1, 1], [1, 0]]
    power = n - 2
    while power:
        if power & 1:
            result = _mat_mul(result, base, mod)
        base = _mat_mul(base, base, mod)
        power >>= 1
    return str((result[0][0] + result[0][1]) % mod)


def _mst(text):
    data = text.split()
    n, m = int(data[0]), int(data[1])
    edges = []
    for i in range(m):
        u, v, w = int(data[2 + 3 * i]), int(data[3 + 3 * i]), int(data[4 + 3 * i])
        edges.append((w, u, v))
    edges.sort()
    parent = list(range(n + 1))

    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]
            x = parent[x]
        return x

    total = 0
    used = 0
    for w, u, v in edges:
        ru, rv = find(u), find(v)
        if ru != rv:
            parent[ru] = rv
            total += w
            used += 1
            if used == n - 1:
                break
    return str(total) if used == n - 1 else "-1"


def _sliding_window(text):
    data = text.split()
    n, k = int(data[0]), int(data[1])
    nums = list(map(int, data[2 : 2 + n]))
    dq = deque()
    out = []
    for i in range(n):
        while dq and dq[0] <= i - k:
            dq.popleft()
        while dq and nums[dq[-1]] <= nums[i]:
            dq.pop()
        dq.append(i)
        if i >= k - 1:
            out.append(str(nums[dq[0]]))
    return " ".join(out)


def _build_random_cases():
    rng = random.Random(777001)
    cases = {}

    topo = []
    for n, m in ((1, 0), (4, 3), (10, 12), (200, 400)):
        rows = []
        for _ in range(m):
            u = rng.randint(1, n - 1) if n > 1 else 1
            v = rng.randint(u + 1, n) if u < n else u
            rows.append("%d %d" % (u, v))
        topo.append("%d %d\n" % (n, m) + "\n".join(rows))
    cases["topo"] = topo

    segs = []
    for n, q in ((5, 5), (20, 30), (200, 200)):
        nums = [rng.randint(-1000, 1000) for _ in range(n)]
        ops = []
        for _ in range(q):
            if rng.random() < 0.6:
                left = rng.randint(1, n)
                right = rng.randint(left, n)
                ops.append("1 %d %d" % (left, right))
            else:
                ops.append("2 %d %d" % (rng.randint(1, n), rng.randint(-1000, 1000)))
        segs.append("%d %d\n" % (n, q) + " ".join(map(str, nums)) + "\n" + "\n".join(ops))
    cases["segment"] = segs

    lcs_cases = []
    for length in (5, 20, 100, 300):
        a = "".join(rng.choice("abcd") for _ in range(length))
        b = "".join(rng.choice("abcd") for _ in range(length))
        lcs_cases.append(a + "\n" + b)
    cases["lcs"] = lcs_cases

    msts = []
    for n, m in ((2, 1), (5, 7), (30, 60), (300, 600)):
        rows = []
        for _ in range(m):
            rows.append("%d %d %d" % (rng.randint(1, n), rng.randint(1, n), rng.randint(1, 1000)))
        msts.append("%d %d\n" % (n, m) + "\n".join(rows))
    cases["mst"] = msts

    windows = []
    for n, k in ((5, 3), (20, 4), (200, 10)):
        nums = [rng.randint(-1000, 1000) for _ in range(n)]
        windows.append("%d %d\n" % (n, k) + " ".join(map(str, nums)))
    cases["window"] = windows

    return cases


_RANDOM = _build_random_cases()


PROBLEMS = [
    {
        "title": "拓扑排序（字典序最小）",
        "summary": "输出有向无环图字典序最小的拓扑序，有环则输出 -1。",
        "description": (
            "给定一个 n 个点、m 条边的有向图，请输出它的一个拓扑序。\n\n"
            "拓扑序要求：对每条有向边 u→v，u 都必须排在 v 之前。\n"
            "若存在多个合法拓扑序，请输出字典序最小的那个。若图中存在环，输出 -1。\n"
            "提示：把入度为 0 的点放入小根堆，每次取出编号最小的点。"
        ),
        "inputFormat": "第一行两个整数 n 和 m（1 ≤ n ≤ 10^5，0 ≤ m ≤ 2×10^5）。\n接下来 m 行，每行两个整数 u、v，表示一条从 u 指向 v 的边（1 ≤ u, v ≤ n）。",
        "outputFormat": "一行。若存在拓扑序，输出 n 个整数表示字典序最小的拓扑序；若存在环，输出 -1。",
        "examples": [
            {
                "input": "4 3\n1 2\n1 3\n3 4",
                "output": "1 2 3 4",
                "explanation": "1 必须先于 2、3，3 必须先于 4，字典序最小的拓扑序为 1 2 3 4。",
            },
            {"input": "3 3\n1 2\n2 3\n3 1", "output": "-1", "explanation": "1→2→3→1 构成环，不存在拓扑序。"},
        ],
        "difficulty": "HARD",
        "tags": ["图论", "拓扑排序", "堆", "有向无环图"],
        "timeLimitMs": 3000,
        "memoryLimitKb": 524288,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _topo_sort,
        "inputs": [
            "4 3\n1 2\n1 3\n3 4",
            "3 3\n1 2\n2 3\n3 1",
            "1 0",
            "3 2\n1 2\n2 3",
            "4 2\n2 3\n1 4",
        ]
        + _RANDOM["topo"],
    },
    {
        "title": "线段树区间求和",
        "summary": "支持单点修改与区间求和查询。",
        "description": (
            "给定一个长度为 n 的整数数组，需要支持 q 次操作：\n\n"
            "操作 1 l r：查询区间 [l, r] 内所有元素之和。\n"
            "操作 2 i x：将第 i 个元素修改为 x。\n\n"
            "下标均从 1 开始。请使用线段树或树状数组实现，单次操作复杂度 O(log n)。"
        ),
        "inputFormat": (
            "第一行两个整数 n 和 q（1 ≤ n, q ≤ 2×10^5）。\n"
            "第二行 n 个整数 ai（-10^4 ≤ ai ≤ 10^4）。\n"
            "接下来 q 行，每行三个整数，格式为 1 l r 或 2 i x（1 ≤ l ≤ r ≤ n，1 ≤ i ≤ n，-10^4 ≤ x ≤ 10^4）。"
        ),
        "outputFormat": "对每次操作 1，输出一行一个整数，表示区间和。",
        "examples": [
            {
                "input": "5 3\n1 2 3 4 5\n1 1 5\n2 3 10\n1 2 4",
                "output": "15\n16",
                "explanation": "初始区间和为 15；把第 3 个元素改为 10 后，区间 [2,4] 的和为 2+10+4=16。",
            }
        ],
        "difficulty": "HARD",
        "tags": ["线段树", "树状数组", "数据结构"],
        "timeLimitMs": 3000,
        "memoryLimitKb": 524288,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _segment_tree,
        "inputs": [
            "5 3\n1 2 3 4 5\n1 1 5\n2 3 10\n1 2 4",
            "1 2\n7\n1 1 1\n2 1 -3",
            "3 4\n-1 -2 -3\n1 1 3\n2 2 0\n1 2 3\n1 1 1",
        ]
        + _RANDOM["segment"],
    },
    {
        "title": "最长公共子序列",
        "summary": "求两个字符串的最长公共子序列长度。",
        "description": (
            "给定两个字符串 a 和 b，求它们的最长公共子序列（LCS）的长度。\n\n"
            "子序列不要求连续，但必须保持字符在原字符串中的相对顺序。\n"
            "例如 abcde 与 ace 的最长公共子序列是 ace，长度为 3。\n"
            "请使用二维动态规划，并用滚动数组优化空间。"
        ),
        "inputFormat": "第一行一个字符串 a，第二行一个字符串 b（1 ≤ |a|, |b| ≤ 500，仅含小写字母）。",
        "outputFormat": "一行，一个整数，表示最长公共子序列的长度。",
        "examples": [
            {"input": "abcde\nace", "output": "3", "explanation": "公共子序列 ace 长度为 3。"},
            {"input": "abc\nxyz", "output": "0", "explanation": "没有公共字符。"},
        ],
        "difficulty": "HARD",
        "tags": ["动态规划", "字符串", "最长公共子序列"],
        "timeLimitMs": 3000,
        "memoryLimitKb": 524288,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _lcs,
        "inputs": ["abcde\nace", "abc\nxyz", "a\na", "aaaa\naa", "algorithm\nlogarithm"]
        + _RANDOM["lcs"],
    },
    {
        "title": "矩阵快速幂求斐波那契",
        "summary": "用矩阵快速幂求第 n 项斐波那契数对 10^9+7 取模。",
        "description": (
            "斐波那契数列满足 F(1) = 1，F(2) = 1，F(n) = F(n-1) + F(n-2)。\n\n"
            "给定 n，求 F(n) mod (10^9+7)。本题 n 可达 10^18，递推会超时，"
            "请使用矩阵快速幂：\n"
            "[F(n+1), F(n)]^T = [[1,1],[1,0]]^(n-1) × [F(2), F(1)]^T。"
        ),
        "inputFormat": "一行，一个整数 n（1 ≤ n ≤ 10^18）。",
        "outputFormat": "一行，一个整数，表示 F(n) mod 1000000007。",
        "examples": [
            {"input": "10", "output": "55", "explanation": "斐波那契数列第 10 项为 55。"},
            {"input": "1", "output": "1", "explanation": "F(1) = 1。"},
        ],
        "difficulty": "HARD",
        "tags": ["数学", "矩阵快速幂", "递推"],
        "timeLimitMs": 3000,
        "memoryLimitKb": 524288,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _fib_fast,
        "inputs": ["10", "1", "2", "100", "1000000", "1000000000000000000", "999999999999999999"],
    },
    {
        "title": "最小生成树",
        "summary": "用 Kruskal 算法求无向连通图的最小生成树权值。",
        "description": (
            "给定一个 n 个点、m 条边的无向带权图，可能存在重边和自环。\n\n"
            "求该图最小生成树（MST）所有边的权值之和。若图不连通，输出 -1。\n"
            "请使用 Kruskal 算法：按边权升序排序，用并查集判断是否成环。"
        ),
        "inputFormat": "第一行两个整数 n 和 m（1 ≤ n ≤ 10^5，0 ≤ m ≤ 2×10^5）。\n接下来 m 行，每行三个整数 u、v、w，表示 u 与 v 之间有一条权值为 w 的边（1 ≤ w ≤ 10^4）。",
        "outputFormat": "一行。若存在最小生成树，输出其权值和；否则输出 -1。",
        "examples": [
            {
                "input": "4 5\n1 2 1\n1 3 4\n2 3 2\n2 4 5\n3 4 3",
                "output": "6",
                "explanation": "选择权值为 1、2、3 的三条边，总权值 6。",
            }
        ],
        "difficulty": "HARD",
        "tags": ["图论", "最小生成树", "并查集", "Kruskal"],
        "timeLimitMs": 3000,
        "memoryLimitKb": 524288,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _mst,
        "inputs": ["4 5\n1 2 1\n1 3 4\n2 3 2\n2 4 5\n3 4 3", "1 0", "3 1\n1 2 5", "2 2\n1 2 3\n1 2 1"]
        + _RANDOM["mst"],
    },
    {
        "title": "滑动窗口最大值",
        "summary": "求每个长度为 k 的滑动窗口中的最大值。",
        "description": (
            "给定一个长度为 n 的整数数组和一个窗口大小 k，窗口从数组最左端开始，每次向右移动一位。\n\n"
            "请输出每个窗口内的最大值。例如数组 1 3 -1 -3 5 3 6 7、k = 3 时，"
            "各窗口最大值依次为 3 3 5 5 6 7。\n"
            "请使用单调队列实现，整体复杂度 O(n)，不要对每个窗口重新扫描。"
        ),
        "inputFormat": "第一行两个整数 n 和 k（1 ≤ k ≤ n ≤ 10^5）。\n第二行 n 个整数 ai（-10^4 ≤ ai ≤ 10^4）。",
        "outputFormat": "一行，共 n-k+1 个整数，依次为每个窗口的最大值，用空格分隔。",
        "examples": [
            {
                "input": "8 3\n1 3 -1 -3 5 3 6 7",
                "output": "3 3 5 5 6 7",
                "explanation": "窗口依次为 [1,3,-1]、[3,-1,-3]、…，对应最大值为 3 3 5 5 6 7。",
            }
        ],
        "difficulty": "HARD",
        "tags": ["单调队列", "滑动窗口", "数组"],
        "timeLimitMs": 3000,
        "memoryLimitKb": 524288,
        "allowedLanguages": ["cpp", "java", "python"],
        "solve": _sliding_window,
        "inputs": ["8 3\n1 3 -1 -3 5 3 6 7", "1 1\n5", "5 5\n1 2 3 4 5", "6 2\n-1 -2 -3 -4 -5 -6"]
        + _RANDOM["window"],
    },
]
