"""题库扩充：24 道函数传参题（LeetCode 风格），生成导入 JSON。

用法：python oj_bank_24.py  ->  写出 tools/oj-bank/oj-bank-24-function.json
每道题自带 Java 参考解（用于导入后真实判题验证）与 Python 参考实现（用于算期望输出）。
排序类返回值的口径写在题面里，Python 与 Java 两份实现按同一口径排序。
"""
import json
import os

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "oj-bank-24-function.json")

PROBLEMS = []

DIFFICULTY = {
    "反转字符串": "EASY",
    "回文数": "EASY",
    "有效的括号": "EASY",
    "合并两个有序链表": "EASY",
    "最大子数组和": "EASY",
    "无重复字符的最长子串": "MEDIUM",
    "最长回文子串": "MEDIUM",
    "三数之和": "MEDIUM",
    "电话号码的字母组合": "MEDIUM",
    "删除链表的倒数第 N 个结点": "MEDIUM",
    "合并区间": "MEDIUM",
    "字母异位词分组": "MEDIUM",
    "旋转图像": "MEDIUM",
    "子集": "MEDIUM",
    "全排列": "MEDIUM",
    "跳跃游戏": "MEDIUM",
    "不同路径": "MEDIUM",
    "最长递增子序列": "MEDIUM",
    "二叉树的中序遍历": "MEDIUM",
    "寻找两个正序数组的中位数": "HARD",
    "最长有效括号": "HARD",
    "接雨水": "HARD",
    "编辑距离": "HARD",
    "正则表达式匹配": "HARD",
}


def add(**kw):
    PROBLEMS.append(kw)


def py_reverse(s):
    return s[::-1]


def py_palindrome_number(x):
    if x < 0:
        return False
    s = str(x)
    return s == s[::-1]


def py_valid(s):
    pairs = {")": "(", "]": "[", "}": "{"}
    stack = []
    for ch in s:
        if ch in "([{":
            stack.append(ch)
        elif ch in pairs:
            if not stack or stack.pop() != pairs[ch]:
                return False
        else:
            return False
    return not stack


def py_merge_lists(a, b):
    i = j = 0
    out = []
    while i < len(a) and j < len(b):
        if a[i] <= b[j]:
            out.append(a[i])
            i += 1
        else:
            out.append(b[j])
            j += 1
    out.extend(a[i:])
    out.extend(b[j:])
    return out


def py_max_subarray(nums):
    best = cur = nums[0]
    for x in nums[1:]:
        cur = max(x, cur + x)
        best = max(best, cur)
    return best


def py_longest_substring(s):
    last = {}
    start = best = 0
    for i, ch in enumerate(s):
        if ch in last and last[ch] >= start:
            start = last[ch] + 1
        last[ch] = i
        best = max(best, i - start + 1)
    return best


def py_longest_palindrome(s):
    if not s:
        return ""
    best_start, best_len = 0, 1
    for center in range(len(s)):
        for left, right in ((center, center), (center, center + 1)):
            while left >= 0 and right < len(s) and s[left] == s[right]:
                left -= 1
                right += 1
            length = right - left - 1
            if length > best_len:
                best_len = length
                best_start = left + 1
    return s[best_start:best_start + best_len]


def py_three_sum(nums):
    nums = sorted(nums)
    out = []
    for i in range(len(nums) - 2):
        if i > 0 and nums[i] == nums[i - 1]:
            continue
        left, right = i + 1, len(nums) - 1
        while left < right:
            total = nums[i] + nums[left] + nums[right]
            if total == 0:
                out.append([nums[i], nums[left], nums[right]])
                left += 1
                right -= 1
                while left < right and nums[left] == nums[left - 1]:
                    left += 1
                while left < right and nums[right] == nums[right + 1]:
                    right -= 1
            elif total < 0:
                left += 1
            else:
                right -= 1
    return out


LETTERS = {
    "2": "abc", "3": "def", "4": "ghi", "5": "jkl",
    "6": "mno", "7": "pqrs", "8": "tuv", "9": "wxyz",
}


def py_letter_combinations(digits):
    if not digits:
        return []
    out = [""]
    for d in digits:
        out = [prefix + ch for prefix in out for ch in LETTERS[d]]
    return out


def py_remove_nth(nums, n):
    idx = len(nums) - n
    return nums[:idx] + nums[idx + 1:]


def py_merge_intervals(intervals):
    if not intervals:
        return []
    rows = sorted(intervals, key=lambda r: (r[0], r[1]))
    out = [rows[0][:]]
    for start, end in rows[1:]:
        if start <= out[-1][1]:
            out[-1][1] = max(out[-1][1], end)
        else:
            out.append([start, end])
    return out


def py_group_anagrams(words):
    groups = {}
    for word in words:
        groups.setdefault("".join(sorted(word)), []).append(word)
    rendered = [",".join(sorted(group)) for group in groups.values()]
    rendered.sort()
    return rendered


def py_rotate(matrix):
    n = len(matrix)
    return [[matrix[n - 1 - j][i] for j in range(n)] for i in range(n)]


def py_subsets(nums):
    out = []
    for mask in range(1 << len(nums)):
        out.append([nums[i] for i in range(len(nums)) if mask >> i & 1])
    out.sort(key=lambda item: (len(item), item))
    return out


def py_permute(nums):
    out = []

    def walk(chosen, rest):
        if not rest:
            out.append(chosen[:])
            return
        for i, value in enumerate(rest):
            walk(chosen + [value], rest[:i] + rest[i + 1:])

    walk([], list(nums))
    out.sort()
    return out


def py_can_jump(nums):
    reach = 0
    for i, value in enumerate(nums):
        if i > reach:
            return False
        reach = max(reach, i + value)
    return True


def py_unique_paths(m, n):
    dp = [1] * n
    for _ in range(1, m):
        for j in range(1, n):
            dp[j] += dp[j - 1]
    return dp[n - 1]


def py_lis(nums):
    import bisect
    tails = []
    for value in nums:
        pos = bisect.bisect_left(tails, value)
        if pos == len(tails):
            tails.append(value)
        else:
            tails[pos] = value
    return len(tails)


def py_inorder(level):
    if not level or level[0] == -1:
        return []
    root = {"v": level[0], "l": None, "r": None}
    queue = [root]
    cursor = 1
    while queue and cursor < len(level):
        node = queue.pop(0)
        for side in ("l", "r"):
            if cursor >= len(level):
                break
            if level[cursor] != -1:
                child = {"v": level[cursor], "l": None, "r": None}
                node[side] = child
                queue.append(child)
            cursor += 1

    order = []

    def walk(node):
        if node is None:
            return
        walk(node["l"])
        order.append(node["v"])
        walk(node["r"])

    walk(root)
    return order


def py_median(a, b):
    merged = sorted(a + b)
    n = len(merged)
    if n % 2:
        return "%.5f" % float(merged[n // 2])
    return "%.5f" % ((merged[n // 2 - 1] + merged[n // 2]) / 2.0)


def py_longest_valid(s):
    stack = [-1]
    best = 0
    for i, ch in enumerate(s):
        if ch == "(":
            stack.append(i)
        else:
            stack.pop()
            if not stack:
                stack.append(i)
            else:
                best = max(best, i - stack[-1])
    return best


def py_trap(height):
    left, right = 0, len(height) - 1
    left_max = right_max = 0
    total = 0
    while left < right:
        if height[left] < height[right]:
            left_max = max(left_max, height[left])
            total += left_max - height[left]
            left += 1
        else:
            right_max = max(right_max, height[right])
            total += right_max - height[right]
            right -= 1
    return total


def py_min_distance(a, b):
    dp = list(range(len(b) + 1))
    for i, ca in enumerate(a, start=1):
        prev, dp[0] = dp[0], i
        for j, cb in enumerate(b, start=1):
            cur = dp[j]
            dp[j] = prev if ca == cb else 1 + min(prev, dp[j], dp[j - 1])
            prev = cur
    return dp[len(b)]


def py_is_match(s, p):
    m, n = len(s), len(p)
    dp = [[False] * (n + 1) for _ in range(m + 1)]
    dp[0][0] = True
    for j in range(2, n + 1):
        if p[j - 1] == "*":
            dp[0][j] = dp[0][j - 2]
    for i in range(1, m + 1):
        for j in range(1, n + 1):
            if p[j - 1] == "*":
                dp[i][j] = dp[i][j - 2]
                if p[j - 2] in (".", s[i - 1]):
                    dp[i][j] = dp[i][j] or dp[i - 1][j]
            elif p[j - 1] in (".", s[i - 1]):
                dp[i][j] = dp[i - 1][j - 1]
    return dp[m][n]


# ---------------------------------------------------------------- EASY

add(
    title="反转字符串",
    summary="实现 reverseString，把输入的字符串整体反转后返回。",
    desc=(
        "给定一个字符串 s，返回它反转后的结果。\n\n"
        "要求：不能使用语言自带的整体反转函数（如 Java 的 StringBuilder#reverse、"
        "Python 的切片 [::-1]），请用双指针或逐字符拼接实现。\n\n"
        "数据范围：0 ≤ s.length ≤ 1000，s 由可打印 ASCII 字符组成。"
    ),
    input_format="函数签名：reverseString(s: string) → string。参数 s 为待反转的字符串。",
    output_format="返回 string：反转后的字符串；空串返回空串。",
    examples=[
        {"input": 's = "hello"', "output": '"olleh"', "explanation": "首尾对称交换后得到 olleh。"},
        {"input": 's = ""', "output": '""', "explanation": "空串反转后仍为空串。"},
    ],
    tags=["字符串", "双指针"],
    fn="reverseString", params=["string"], ret="string", time=1000, mem=262144,
    java='''class Solution {
    public String reverseString(String s) {
        char[] chars = s.toCharArray();
        int left = 0, right = chars.length - 1;
        while (left < right) {
            char temp = chars[left];
            chars[left] = chars[right];
            chars[right] = temp;
            left++;
            right--;
        }
        return new String(chars);
    }
}''',
    py=py_reverse,
    cases=[["hello"], ["Hannah"], [""], ["a"], ["A man a plan"]],
    practice=[0, 2],
)

add(
    title="回文数",
    summary="实现 isPalindromeNumber，判断一个整数正着读和倒着读是否相同。",
    desc=(
        "给定一个整数 x，如果 x 是一个回文整数，返回 true；否则返回 false。\n\n"
        "回文数是指正序（从左向右）和倒序（从右向左）读都相同的整数。例如 121 是回文数，"
        "而 123 不是。负数不是回文数（负号不参与对称）。\n\n"
        "数据范围：-2^31 ≤ x ≤ 2^31 - 1。"
    ),
    input_format="函数签名：isPalindromeNumber(x: int) → bool。参数 x 为待判断的整数。",
    output_format="返回 bool：是回文数返回 true，否则返回 false。",
    examples=[
        {"input": "x = 121", "output": "true", "explanation": "从左读和从右读都是 121。"},
        {"input": "x = -121", "output": "false", "explanation": "从右读是 121-，不相等；负数一律不是回文数。"},
    ],
    tags=["数学", "字符串"],
    fn="isPalindromeNumber", params=["int"], ret="bool", time=1000, mem=262144,
    java='''class Solution {
    public boolean isPalindromeNumber(int x) {
        if (x < 0) return false;
        if (x < 10) return true;
        String text = Integer.toString(x);
        int left = 0, right = text.length() - 1;
        while (left < right) {
            if (text.charAt(left) != text.charAt(right)) return false;
            left++;
            right--;
        }
        return true;
    }
}''',
    py=py_palindrome_number,
    cases=[[121], [-121], [10], [0], [12321], [1000021]],
    practice=[0, 1],
)

add(
    title="有效的括号",
    summary="实现 isValid，判断只含 ()[]{} 的字符串是否括号配对正确。",
    desc=(
        "给定一个只包括 '('、')'、'['、']'、'{'、'}' 的字符串 s，判断字符串是否有效。\n\n"
        "有效字符串需满足：\n"
        "1. 左括号必须用相同类型的右括号闭合；\n"
        "2. 左括号必须以正确的顺序闭合；\n"
        "3. 每个右括号都有一个对应的相同类型的左括号。\n\n"
        "数据范围：0 ≤ s.length ≤ 10^4，s 仅由上述六种括号组成。"
    ),
    input_format="函数签名：isValid(s: string) → bool。参数 s 为括号串。",
    output_format="返回 bool：括号完全配对返回 true，否则返回 false。空串视为有效。",
    examples=[
        {"input": 's = "()[]{}"', "output": "true", "explanation": "三对括号依次正确闭合。"},
        {"input": 's = "([)]"', "output": "false", "explanation": "小括号未闭合就遇到右中括号，顺序错误。"},
    ],
    tags=["栈", "字符串"],
    fn="isValid", params=["string"], ret="bool", time=1000, mem=262144,
    java='''class Solution {
    public boolean isValid(String s) {
        java.util.Deque<Character> stack = new java.util.ArrayDeque<>();
        for (char ch : s.toCharArray()) {
            if (ch == '(' || ch == '[' || ch == '{') {
                stack.push(ch);
            } else {
                if (stack.isEmpty()) return false;
                char top = stack.pop();
                if (ch == ')' && top != '(') return false;
                if (ch == ']' && top != '[') return false;
                if (ch == '}' && top != '{') return false;
            }
        }
        return stack.isEmpty();
    }
}''',
    py=py_valid,
    cases=[["()"], ["()[]{}"], ["(]"], ["([)]"], ["{[]}"], [""], ["((("]],
    practice=[0, 2],
)

add(
    title="合并两个有序链表",
    summary="实现 mergeTwoLists，把两个升序数组表示的有序链表合并成一个升序数组。",
    desc=(
        "给定两个按非递减顺序排列的整数数组 list1 和 list2，把它们看作两个有序链表，"
        "合并成一个新的有序链表，并返回合并后各结点的值组成的数组。\n\n"
        "要求：合并结果保持非递减顺序；相同元素都要保留（不去重）。\n\n"
        "数据范围：0 ≤ list1.length, list2.length ≤ 1000，-1000 ≤ 结点值 ≤ 1000。"
    ),
    input_format="函数签名：mergeTwoLists(list1: int[], list2: int[]) → int[]。两个参数都是升序数组。",
    output_format="返回 int[]：合并后的升序数组；两个都为空时返回空数组。",
    examples=[
        {"input": "list1 = [1,2,4], list2 = [1,3,4]", "output": "[1,1,2,3,4,4]", "explanation": "逐个比较头结点，取较小者接到结果尾部。"},
        {"input": "list1 = [], list2 = [0]", "output": "[0]", "explanation": "空链表与单元素链表合并，结果就是那个元素。"},
    ],
    tags=["链表", "双指针", "排序"],
    fn="mergeTwoLists", params=["int[]", "int[]"], ret="int[]", time=1000, mem=262144,
    java='''class Solution {
    public int[] mergeTwoLists(int[] list1, int[] list2) {
        int[] out = new int[list1.length + list2.length];
        int i = 0, j = 0, k = 0;
        while (i < list1.length && j < list2.length) {
            if (list1[i] <= list2[j]) out[k++] = list1[i++];
            else out[k++] = list2[j++];
        }
        while (i < list1.length) out[k++] = list1[i++];
        while (j < list2.length) out[k++] = list2[j++];
        return out;
    }
}''',
    py=py_merge_lists,
    cases=[
        [[1, 2, 4], [1, 3, 4]],
        [[], []],
        [[], [0]],
        [[5], [1, 2, 3]],
        [[1, 1, 1], [1, 1]],
    ],
    practice=[0, 2],
)

add(
    title="最大子数组和",
    summary="实现 maxSubArray，求连续子数组的最大和。",
    desc=(
        "给定一个整数数组 nums，请找出一个具有最大和的连续子数组（至少包含一个元素），"
        "返回其最大和。\n\n"
        "子数组是数组中的连续部分，不能跳着取。数组可能全为负数，此时答案是最大的那个负数。\n\n"
        "数据范围：1 ≤ nums.length ≤ 10^5，-10^4 ≤ nums[i] ≤ 10^4。"
    ),
    input_format="函数签名：maxSubArray(nums: int[]) → int。参数 nums 为非空整数数组。",
    output_format="返回 int：连续子数组的最大和。",
    examples=[
        {"input": "nums = [-2,1,-3,4,-1,2,1,-5,4]", "output": "6", "explanation": "连续子数组 [4,-1,2,1] 的和最大，为 6。"},
        {"input": "nums = [-3,-1,-2]", "output": "-1", "explanation": "全为负数时，最大和是单个元素 -1。"},
    ],
    tags=["数组", "动态规划", "贪心"],
    fn="maxSubArray", params=["int[]"], ret="int", time=1000, mem=262144,
    java='''class Solution {
    public int maxSubArray(int[] nums) {
        int best = nums[0], current = nums[0];
        for (int i = 1; i < nums.length; i++) {
            current = Math.max(nums[i], current + nums[i]);
            best = Math.max(best, current);
        }
        return best;
    }
}''',
    py=py_max_subarray,
    cases=[
        [[-2, 1, -3, 4, -1, 2, 1, -5, 4]],
        [[1]],
        [[5, 4, -1, 7, 8]],
        [[-3, -1, -2]],
        [[-1]],
    ],
    practice=[0, 2],
)

# ---------------------------------------------------------------- MEDIUM

add(
    title="无重复字符的最长子串",
    summary="实现 lengthOfLongestSubstring，求不含重复字符的最长连续子串长度。",
    desc=(
        "给定一个字符串 s，请找出其中不含重复字符的最长子串的长度。\n\n"
        "注意是子串（连续）而不是子序列。字符串可能包含空格、数字和符号，"
        "也可能为空。\n\n"
        "数据范围：0 ≤ s.length ≤ 5 * 10^4，s 由 ASCII 字符组成。"
    ),
    input_format="函数签名：lengthOfLongestSubstring(s: string) → int。参数 s 为待分析字符串。",
    output_format="返回 int：最长无重复字符子串的长度；空串返回 0。",
    examples=[
        {"input": 's = "abcabcbb"', "output": "3", "explanation": "最长无重复子串是 abc，长度为 3。"},
        {"input": 's = "bbbbb"', "output": "1", "explanation": "所有字符相同，最长子串是单个 b。"},
    ],
    tags=["字符串", "滑动窗口", "哈希表"],
    fn="lengthOfLongestSubstring", params=["string"], ret="int", time=1000, mem=262144,
    java='''class Solution {
    public int lengthOfLongestSubstring(String s) {
        int[] last = new int[256];
        java.util.Arrays.fill(last, -1);
        int start = 0, best = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (last[ch] >= start) start = last[ch] + 1;
            last[ch] = i;
            best = Math.max(best, i - start + 1);
        }
        return best;
    }
}''',
    py=py_longest_substring,
    cases=[["abcabcbb"], ["bbbbb"], ["pwwkew"], [""], ["dvdf"], ["abba"]],
    practice=[0, 1],
)

add(
    title="最长回文子串",
    summary="实现 longestPalindrome，返回最长回文子串（多个同样长时取最靠前的）。",
    desc=(
        "给定一个字符串 s，找到 s 中最长的回文子串并返回。\n\n"
        "口径：如果有多个长度相同的最长回文子串，返回起始下标最小的那一个。\n\n"
        "数据范围：1 ≤ s.length ≤ 1000，s 仅由数字和英文字母组成。"
    ),
    input_format="函数签名：longestPalindrome(s: string) → string。参数 s 为非空字符串。",
    output_format="返回 string：最长回文子串；长度相同时取起始位置最靠前的。",
    examples=[
        {"input": 's = "babad"', "output": '"bab"', "explanation": "bab 与 aba 都是长度 3 的回文子串，bab 的起始下标更小。"},
        {"input": 's = "cbbd"', "output": '"bb"', "explanation": "最长回文子串是 bb，长度为 2。"},
    ],
    tags=["字符串", "动态规划", "中心扩展"],
    fn="longestPalindrome", params=["string"], ret="string", time=1000, mem=262144,
    java='''class Solution {
    public String longestPalindrome(String s) {
        if (s == null || s.isEmpty()) return "";
        int bestStart = 0, bestLen = 1;
        for (int center = 0; center < s.length(); center++) {
            for (int[] pair : new int[][] {{center, center}, {center, center + 1}}) {
                int left = pair[0], right = pair[1];
                while (left >= 0 && right < s.length() && s.charAt(left) == s.charAt(right)) {
                    left--;
                    right++;
                }
                int length = right - left - 1;
                if (length > bestLen) {
                    bestLen = length;
                    bestStart = left + 1;
                }
            }
        }
        return s.substring(bestStart, bestStart + bestLen);
    }
}''',
    py=py_longest_palindrome,
    cases=[["babad"], ["cbbd"], ["a"], ["ac"], ["aaaa"], ["forgeeksskeegfor"]],
    practice=[0, 1],
)

add(
    title="三数之和",
    summary="实现 threeSum，找出所有和为 0 且不重复的三元组。",
    desc=(
        "给定一个整数数组 nums，判断其中是否存在三元组 [nums[i], nums[j], nums[k]] "
        "满足 i、j、k 互不相同且 nums[i] + nums[j] + nums[k] == 0。\n\n"
        "返回所有和为 0 且不重复的三元组。输出口径：每个三元组内部按升序排列，"
        "整体按字典序（先比第一个元素，再比第二个，最后比第三个）升序返回。\n\n"
        "数据范围：0 ≤ nums.length ≤ 3000，-10^5 ≤ nums[i] ≤ 10^5。"
    ),
    input_format="函数签名：threeSum(nums: int[]) → int[][]。参数 nums 为整数数组，可为空。",
    output_format="返回 int[][]：所有不重复的零和三元组；没有时返回空数组。",
    examples=[
        {"input": "nums = [-1,0,1,2,-1,-4]", "output": "[[-1,-1,2],[-1,0,1]]", "explanation": "去重后只有这两组和为 0。"},
        {"input": "nums = [0,1,1]", "output": "[]", "explanation": "不存在满足条件的三元组。"},
    ],
    tags=["数组", "双指针", "排序"],
    fn="threeSum", params=["int[]"], ret="int[][]", time=2000, mem=262144,
    java='''class Solution {
    public int[][] threeSum(int[] nums) {
        java.util.Arrays.sort(nums);
        java.util.List<int[]> out = new java.util.ArrayList<>();
        for (int i = 0; i + 2 < nums.length; i++) {
            if (i > 0 && nums[i] == nums[i - 1]) continue;
            int left = i + 1, right = nums.length - 1;
            while (left < right) {
                int total = nums[i] + nums[left] + nums[right];
                if (total == 0) {
                    out.add(new int[] {nums[i], nums[left], nums[right]});
                    left++;
                    right--;
                    while (left < right && nums[left] == nums[left - 1]) left++;
                    while (left < right && nums[right] == nums[right + 1]) right--;
                } else if (total < 0) {
                    left++;
                } else {
                    right--;
                }
            }
        }
        return out.toArray(new int[0][]);
    }
}''',
    py=py_three_sum,
    cases=[
        [[-1, 0, 1, 2, -1, -4]],
        [[]],
        [[0]],
        [[0, 0, 0]],
        [[-2, 0, 1, 1, 2]],
        [[1, 2, -2, -1]],
    ],
    practice=[0, 3],
)

add(
    title="电话号码的字母组合",
    summary="实现 letterCombinations，返回电话号码数字能表示的所有字母组合。",
    desc=(
        "给定一个仅包含数字 2-9 的字符串 digits，返回它能表示的所有字母组合。\n\n"
        "数字到字母的映射与电话按键相同：2→abc，3→def，4→ghi，5→jkl，6→mno，"
        "7→pqrs，8→tuv，9→wxyz。\n\n"
        "输出口径：按数字顺序深度优先、每个数字内部按字母表顺序生成，先变最后一位。\n"
        "例如 digits = \"23\" 返回 [ad, ae, af, bd, be, bf, cd, ce, cf]。digits 为空时返回空数组。\n\n"
        "数据范围：0 ≤ digits.length ≤ 4，digits[i] 是 2-9 的数字。"
    ),
    input_format="函数签名：letterCombinations(digits: string) → string[]。参数 digits 为数字串，可为空。",
    output_format="返回 string[]：所有字母组合，顺序见题面口径；digits 为空时返回空数组。",
    examples=[
        {"input": 'digits = "23"', "output": '["ad","ae","af","bd","be","bf","cd","ce","cf"]', "explanation": "2 对应 abc，3 对应 def，两两组合按序输出。"},
        {"input": 'digits = ""', "output": "[]", "explanation": "没有数字时不存在任何组合。"},
    ],
    tags=["字符串", "回溯", "哈希表"],
    fn="letterCombinations", params=["string"], ret="string[]", time=1000, mem=262144,
    java='''class Solution {
    private static final String[] MAP = {
        "", "", "abc", "def", "ghi", "jkl", "mno", "pqrs", "tuv", "wxyz"
    };

    public String[] letterCombinations(String digits) {
        if (digits == null || digits.isEmpty()) return new String[0];
        java.util.List<String> out = new java.util.ArrayList<>();
        walk(digits, 0, new StringBuilder(), out);
        return out.toArray(new String[0]);
    }

    private void walk(String digits, int index, StringBuilder path, java.util.List<String> out) {
        if (index == digits.length()) {
            out.add(path.toString());
            return;
        }
        for (char ch : MAP[digits.charAt(index) - '0'].toCharArray()) {
            path.append(ch);
            walk(digits, index + 1, path, out);
            path.deleteCharAt(path.length() - 1);
        }
    }
}''',
    py=py_letter_combinations,
    cases=[["23"], [""], ["2"], ["79"], ["22"]],
    practice=[0, 2],
)

add(
    title="删除链表的倒数第 N 个结点",
    summary="实现 removeNthFromEnd，删除倒数第 n 个结点后返回剩余结点值。",
    desc=(
        "给定一个整数数组 head 表示链表的结点值（按链表顺序），再给定整数 n，"
        "删除链表的倒数第 n 个结点，返回剩余结点值组成的数组。\n\n"
        "要求：一次遍历完成（快慢指针），不要先求长度再删。\n\n"
        "数据范围：1 ≤ head.length ≤ 30，0 ≤ 结点值 ≤ 100，1 ≤ n ≤ head.length。"
    ),
    input_format="函数签名：removeNthFromEnd(head: int[], n: int) → int[]。head 为链表值序列，n 为倒数序号。",
    output_format="返回 int[]：删除后的链表值序列；删空时返回空数组。",
    examples=[
        {"input": "head = [1,2,3,4,5], n = 2", "output": "[1,2,3,5]", "explanation": "倒数第 2 个是 4，删除后剩下 1,2,3,5。"},
        {"input": "head = [1], n = 1", "output": "[]", "explanation": "只有一个结点，删除后链表为空。"},
    ],
    tags=["链表", "双指针"],
    fn="removeNthFromEnd", params=["int[]", "int"], ret="int[]", time=1000, mem=262144,
    java='''class Solution {
    public int[] removeNthFromEnd(int[] head, int n) {
        int[] out = new int[head.length - 1];
        int removeIndex = head.length - n;
        int k = 0;
        for (int i = 0; i < head.length; i++) {
            if (i == removeIndex) continue;
            out[k++] = head[i];
        }
        return out;
    }
}''',
    py=py_remove_nth,
    cases=[
        [[1, 2, 3, 4, 5], 2],
        [[1], 1],
        [[1, 2], 1],
        [[1, 2], 2],
        [[1, 2, 3], 3],
    ],
    practice=[0, 1],
)

add(
    title="合并区间",
    summary="实现 mergeIntervals，合并所有重叠区间并返回升序结果。",
    desc=(
        "以数组 intervals 表示若干区间的集合，其中 intervals[i] = [start_i, end_i]。"
        "请合并所有重叠的区间，返回互不重叠且覆盖输入中所有区间的结果。\n\n"
        "输出口径：结果按区间起点升序排列；相邻区间若端点相接（如 [1,4] 与 [4,5]）也要合并。\n\n"
        "数据范围：0 ≤ intervals.length ≤ 10^4，intervals[i].length == 2，0 ≤ start ≤ end ≤ 10^4。"
    ),
    input_format="函数签名：mergeIntervals(intervals: int[][]) → int[][]。参数为二维数组，每行两个整数。",
    output_format="返回 int[][]：合并后按起点升序排列的区间；输入为空时返回空数组。",
    examples=[
        {"input": "intervals = [[1,3],[2,6],[8,10],[15,18]]", "output": "[[1,6],[8,10],[15,18]]", "explanation": "[1,3] 与 [2,6] 重叠，合并为 [1,6]。"},
        {"input": "intervals = [[1,4],[4,5]]", "output": "[[1,5]]", "explanation": "端点 4 相接，视为重叠。"},
    ],
    tags=["数组", "排序", "区间"],
    fn="mergeIntervals", params=["int[][]"], ret="int[][]", time=1000, mem=262144,
    java='''class Solution {
    public int[][] mergeIntervals(int[][] intervals) {
        if (intervals.length == 0) return new int[0][];
        int[][] rows = new int[intervals.length][];
        for (int i = 0; i < intervals.length; i++) rows[i] = intervals[i].clone();
        java.util.Arrays.sort(rows, (a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
        java.util.List<int[]> out = new java.util.ArrayList<>();
        out.add(new int[] {rows[0][0], rows[0][1]});
        for (int i = 1; i < rows.length; i++) {
            int[] last = out.get(out.size() - 1);
            if (rows[i][0] <= last[1]) {
                last[1] = Math.max(last[1], rows[i][1]);
            } else {
                out.add(new int[] {rows[i][0], rows[i][1]});
            }
        }
        return out.toArray(new int[0][]);
    }
}''',
    py=py_merge_intervals,
    cases=[
        [[[1, 3], [2, 6], [8, 10], [15, 18]]],
        [[[1, 4], [4, 5]]],
        [[[1, 4], [0, 4]]],
        [[[1, 4], [2, 3]]],
        [[[1, 2]]],
        [[]],
    ],
    practice=[0, 1],
)

add(
    title="字母异位词分组",
    summary="实现 groupAnagrams，把字母异位词分组（按题面口径拼成字符串返回）。",
    desc=(
        "给定一个字符串数组 words，把其中的字母异位词分到同一组。字母异位词指字母种类和数量完全相同、"
        "只是排列顺序不同的单词。\n\n"
        "输出口径（本平台返回类型不支持二维字符串数组，因此按下面规则拍平）：\n"
        "1. 每组内部把单词按字典序升序排列，再用英文逗号连接成一个字符串；\n"
        "2. 所有组按组内第一个单词的字典序升序排列。\n"
        "例如 [\"eat\",\"tea\",\"tan\",\"ate\",\"nat\",\"bat\"] 返回 "
        "[\"ate,eat,tea\",\"bat\",\"nat,tan\"]。\n\n"
        "数据范围：1 ≤ words.length ≤ 10^4，0 ≤ words[i].length ≤ 100，单词仅含小写字母。"
    ),
    input_format="函数签名：groupAnagrams(words: string[]) → string[]。参数为单词数组。",
    output_format="返回 string[]：每组一个字符串（组内单词按字典序用逗号连接），组间按首词字典序升序。",
    examples=[
        {"input": 'words = ["eat","tea","tan","ate","nat","bat"]', "output": '["ate,eat,tea","bat","nat,tan"]', "explanation": "ate/eat/tea 互为异位词，bat 单独一组，nat/tan 一组。"},
        {"input": 'words = [""]', "output": '[""]', "explanation": "空串自成一組，连接后仍是空串。"},
    ],
    tags=["哈希表", "字符串", "排序"],
    fn="groupAnagrams", params=["string[]"], ret="string[]", time=1000, mem=262144,
    java='''class Solution {
    public String[] groupAnagrams(String[] words) {
        java.util.Map<String, java.util.List<String>> groups = new java.util.LinkedHashMap<>();
        for (String word : words) {
            char[] chars = word.toCharArray();
            java.util.Arrays.sort(chars);
            String key = new String(chars);
            groups.computeIfAbsent(key, k -> new java.util.ArrayList<>()).add(word);
        }
        java.util.List<String> rendered = new java.util.ArrayList<>();
        for (java.util.List<String> group : groups.values()) {
            java.util.Collections.sort(group);
            rendered.add(String.join(",", group));
        }
        java.util.Collections.sort(rendered);
        return rendered.toArray(new String[0]);
    }
}''',
    py=py_group_anagrams,
    cases=[
        [["eat", "tea", "tan", "ate", "nat", "bat"]],
        [[""]],
        [["a"]],
        [["ab", "ba", "abc", "cba", "bca"]],
    ],
    practice=[0, 1],
)

add(
    title="旋转图像",
    summary="实现 rotate，把 n×n 矩阵顺时针旋转 90 度后返回。",
    desc=(
        "给定一个 n × n 的二维矩阵 matrix 表示一张图像，请将它顺时针旋转 90 度，返回旋转后的矩阵。\n\n"
        "要求：必须原地旋转（先转置再左右翻转，或四向交换），不要新建一个完整矩阵再逐格复制"
        "（返回值可以是新矩阵，但旋转过程应在原矩阵上完成）。\n\n"
        "数据范围：1 ≤ n ≤ 100，-1000 ≤ matrix[i][j] ≤ 1000。"
    ),
    input_format="函数签名：rotate(matrix: int[][]) → int[][]。参数为 n×n 矩阵。",
    output_format="返回 int[][]：顺时针旋转 90 度后的矩阵。",
    examples=[
        {"input": "matrix = [[1,2,3],[4,5,6],[7,8,9]]", "output": "[[7,4,1],[8,5,2],[9,6,3]]", "explanation": "第一列变成第一行（自下而上）。"},
        {"input": "matrix = [[1]]", "output": "[[1]]", "explanation": "1×1 矩阵旋转后不变。"},
    ],
    tags=["数组", "矩阵", "原地算法"],
    fn="rotate", params=["int[][]"], ret="int[][]", time=1000, mem=262144,
    java='''class Solution {
    public int[][] rotate(int[][] matrix) {
        int n = matrix.length;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                int temp = matrix[i][j];
                matrix[i][j] = matrix[j][i];
                matrix[j][i] = temp;
            }
        }
        for (int i = 0; i < n; i++) {
            for (int left = 0, right = n - 1; left < right; left++, right--) {
                int temp = matrix[i][left];
                matrix[i][left] = matrix[i][right];
                matrix[i][right] = temp;
            }
        }
        return matrix;
    }
}''',
    py=py_rotate,
    cases=[
        [[[1, 2, 3], [4, 5, 6], [7, 8, 9]]],
        [[[1]]],
        [[[1, 2], [3, 4]]],
        [[[5, 1, 9, 11], [2, 4, 8, 10], [13, 3, 6, 7], [15, 14, 12, 16]]],
    ],
    practice=[0, 1],
)

add(
    title="子集",
    summary="实现 subsets，返回整数数组的所有子集（按长度、字典序排列）。",
    desc=(
        "给定一个不含重复元素的整数数组 nums，返回它的所有子集（幂集）。\n\n"
        "输出口径：按子集长度升序排列；长度相同时按字典序（逐元素比较）升序排列。"
        "空集排在最前面。例如 [1,2,3] 返回 "
        "[[],[1],[2],[3],[1,2],[1,3],[2,3],[1,2,3]]。\n\n"
        "数据范围：1 ≤ nums.length ≤ 10，-10 ≤ nums[i] ≤ 10，元素互不相同。"
    ),
    input_format="函数签名：subsets(nums: int[]) → int[][]。参数 nums 为互不相同的整数数组。",
    output_format="返回 int[][]：所有子集，顺序见题面口径。",
    examples=[
        {"input": "nums = [1,2,3]", "output": "[[],[1],[2],[3],[1,2],[1,3],[2,3],[1,2,3]]", "explanation": "共 2^3 = 8 个子集，按长度和字典序排列。"},
        {"input": "nums = [0]", "output": "[[],[0]]", "explanation": "只有一个元素时，子集为空集和它本身。"},
    ],
    tags=["数组", "回溯", "位运算"],
    fn="subsets", params=["int[]"], ret="int[][]", time=1000, mem=262144,
    java='''class Solution {
    public int[][] subsets(int[] nums) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        int total = 1 << nums.length;
        for (int mask = 0; mask < total; mask++) {
            int count = Integer.bitCount(mask);
            int[] item = new int[count];
            int k = 0;
            for (int i = 0; i < nums.length; i++) if ((mask >> i & 1) == 1) item[k++] = nums[i];
            out.add(item);
        }
        out.sort((a, b) -> {
            if (a.length != b.length) return Integer.compare(a.length, b.length);
            for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
            return 0;
        });
        return out.toArray(new int[0][]);
    }
}''',
    py=py_subsets,
    cases=[
        [[1, 2, 3]],
        [[0]],
        [[1, 2]],
        [[5, 3, 9]],
    ],
    practice=[0, 1],
)

add(
    title="全排列",
    summary="实现 permute，返回数组的所有全排列（按字典序升序）。",
    desc=(
        "给定一个不含重复数字的数组 nums，返回其所有可能的全排列。\n\n"
        "输出口径：所有排列按字典序（逐元素比较）升序返回。"
        "例如 [1,2,3] 返回 [[1,2,3],[1,3,2],[2,1,3],[2,3,1],[3,1,2],[3,2,1]]。\n\n"
        "数据范围：1 ≤ nums.length ≤ 6，-10 ≤ nums[i] ≤ 10，元素互不相同。"
    ),
    input_format="函数签名：permute(nums: int[]) → int[][]。参数 nums 为互不相同的整数数组。",
    output_format="返回 int[][]：所有全排列，按字典序升序。",
    examples=[
        {"input": "nums = [1,2,3]", "output": "[[1,2,3],[1,3,2],[2,1,3],[2,3,1],[3,1,2],[3,2,1]]", "explanation": "3 个元素共 6 种排列。"},
        {"input": "nums = [0,1]", "output": "[[0,1],[1,0]]", "explanation": "2 个元素共 2 种排列。"},
    ],
    tags=["数组", "回溯"],
    fn="permute", params=["int[]"], ret="int[][]", time=1000, mem=262144,
    java='''class Solution {
    public int[][] permute(int[] nums) {
        java.util.List<int[]> out = new java.util.ArrayList<>();
        walk(nums, new boolean[nums.length], new int[nums.length], 0, out);
        out.sort((a, b) -> {
            for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return Integer.compare(a[i], b[i]);
            return 0;
        });
        return out.toArray(new int[0][]);
    }

    private void walk(int[] nums, boolean[] used, int[] path, int depth, java.util.List<int[]> out) {
        if (depth == nums.length) {
            out.add(path.clone());
            return;
        }
        for (int i = 0; i < nums.length; i++) {
            if (used[i]) continue;
            used[i] = true;
            path[depth] = nums[i];
            walk(nums, used, path, depth + 1, out);
            used[i] = false;
        }
    }
}''',
    py=py_permute,
    cases=[
        [[1, 2, 3]],
        [[0, 1]],
        [[1]],
        [[2, 4, 6]],
    ],
    practice=[0, 1],
)

add(
    title="跳跃游戏",
    summary="实现 canJump，判断能否从第一个下标跳到最后一个下标。",
    desc=(
        "给定一个非负整数数组 nums，你最初位于第一个下标。数组中的每个元素代表你在该位置"
        "可以跳跃的最大长度。判断你是否能够到达最后一个下标。\n\n"
        "例如 nums = [2,3,1,1,4] 可以先跳 1 步到下标 1，再跳 3 步到最后一个下标。\n\n"
        "数据范围：1 ≤ nums.length ≤ 10^4，0 ≤ nums[i] ≤ 10^5。"
    ),
    input_format="函数签名：canJump(nums: int[]) → bool。参数 nums 为跳跃长度数组。",
    output_format="返回 bool：能到达最后一个下标返回 true，否则 false。",
    examples=[
        {"input": "nums = [2,3,1,1,4]", "output": "true", "explanation": "下标 0 → 1 → 4，可以到达终点。"},
        {"input": "nums = [3,2,1,0,4]", "output": "false", "explanation": "无论怎么跳都会停在下标 3（值为 0），到不了 4。"},
    ],
    tags=["数组", "贪心"],
    fn="canJump", params=["int[]"], ret="bool", time=1000, mem=262144,
    java='''class Solution {
    public boolean canJump(int[] nums) {
        int reach = 0;
        for (int i = 0; i < nums.length; i++) {
            if (i > reach) return false;
            reach = Math.max(reach, i + nums[i]);
        }
        return true;
    }
}''',
    py=py_can_jump,
    cases=[
        [[2, 3, 1, 1, 4]],
        [[3, 2, 1, 0, 4]],
        [[0]],
        [[0, 1]],
        [[2, 0, 0]],
    ],
    practice=[0, 1],
)

add(
    title="不同路径",
    summary="实现 uniquePaths，计算机器人从左上角到右下角的路径数。",
    desc=(
        "一个机器人位于一个 m × n 网格的左上角，每次只能向下或向右移动一步。"
        "机器人试图达到网格的右下角，问总共有多少条不同的路径。\n\n"
        "数据范围：1 ≤ m, n ≤ 20，结果保证在 int 范围内。"
    ),
    input_format="函数签名：uniquePaths(m: int, n: int) → int。m 为行数，n 为列数。",
    output_format="返回 int：从左上角到右下角的路径总数。",
    examples=[
        {"input": "m = 3, n = 7", "output": "28", "explanation": "从 12 步中选 2 步向下，共 C(12,2)=66 种压缩后为 28。"},
        {"input": "m = 3, n = 2", "output": "3", "explanation": "向右 1 步、向下 2 步，共 3 种走法。"},
    ],
    tags=["动态规划", "数学"],
    fn="uniquePaths", params=["int", "int"], ret="int", time=1000, mem=262144,
    java='''class Solution {
    public int uniquePaths(int m, int n) {
        int[] dp = new int[n];
        java.util.Arrays.fill(dp, 1);
        for (int i = 1; i < m; i++) {
            for (int j = 1; j < n; j++) dp[j] += dp[j - 1];
        }
        return dp[n - 1];
    }
}''',
    py=py_unique_paths,
    cases=[[3, 7], [3, 2], [1, 1], [10, 10], [1, 5]],
    practice=[0, 1],
)

add(
    title="最长递增子序列",
    summary="实现 lengthOfLIS，求最长严格递增子序列的长度。",
    desc=(
        "给定一个整数数组 nums，找到其中最长严格递增子序列的长度。\n\n"
        "子序列是由数组派生而来的序列，删除（或不删除）数组中的元素而不改变其余元素的顺序，"
        "不要求连续。严格递增指后一个元素必须大于前一个元素。\n\n"
        "数据范围：1 ≤ nums.length ≤ 2500，-10^4 ≤ nums[i] ≤ 10^4。"
    ),
    input_format="函数签名：lengthOfLIS(nums: int[]) → int。参数 nums 为非空整数数组。",
    output_format="返回 int：最长严格递增子序列的长度。",
    examples=[
        {"input": "nums = [10,9,2,5,3,7,101,18]", "output": "4", "explanation": "最长递增子序列是 [2,3,7,101]，长度为 4。"},
        {"input": "nums = [7,7,7,7]", "output": "1", "explanation": "严格递增，重复元素不能连在一起。"},
    ],
    tags=["数组", "动态规划", "二分查找"],
    fn="lengthOfLIS", params=["int[]"], ret="int", time=1000, mem=262144,
    java='''class Solution {
    public int lengthOfLIS(int[] nums) {
        int[] tails = new int[nums.length];
        int size = 0;
        for (int value : nums) {
            int left = 0, right = size;
            while (left < right) {
                int mid = (left + right) >>> 1;
                if (tails[mid] < value) left = mid + 1;
                else right = mid;
            }
            tails[left] = value;
            if (left == size) size++;
        }
        return size;
    }
}''',
    py=py_lis,
    cases=[
        [[10, 9, 2, 5, 3, 7, 101, 18]],
        [[0, 1, 0, 3, 2, 3]],
        [[7, 7, 7, 7]],
        [[1]],
        [[4, 10, 4, 3, 8, 9]],
    ],
    practice=[0, 2],
)

add(
    title="二叉树的中序遍历",
    summary="实现 inorderTraversal，返回二叉树的中序遍历序列（层序数组输入）。",
    desc=(
        "给定一棵二叉树的层序数组 level，按中序遍历（左-根-右）返回结点值序列。\n\n"
        "输入口径：level 是层序（逐层从左到右）排列的结点值，空位用 -1 表示。"
        "例如 [1,-1,2,3] 表示根为 1、左子树为空、右孩子为 2，2 的左孩子为 3。\n\n"
        "数据范围：0 ≤ level.length ≤ 100，1 ≤ 结点值 ≤ 1000，-1 仅表示空位。"
    ),
    input_format="函数签名：inorderTraversal(level: int[]) → int[]。level 为层序数组，-1 表示空位。",
    output_format="返回 int[]：中序遍历的结点值序列；空树返回空数组。",
    examples=[
        {"input": "level = [1,-1,2,3]", "output": "[1,3,2]", "explanation": "根 1 无左孩子，右孩子 2 的左孩子是 3，中序为 1,3,2。"},
        {"input": "level = []", "output": "[]", "explanation": "空树中序遍历为空。"},
    ],
    tags=["树", "栈", "递归"],
    fn="inorderTraversal", params=["int[]"], ret="int[]", time=1000, mem=262144,
    java='''class Solution {
    private static class Node {
        int value;
        Node left, right;
        Node(int value) { this.value = value; }
    }

    public int[] inorderTraversal(int[] level) {
        if (level.length == 0 || level[0] == -1) return new int[0];
        Node root = new Node(level[0]);
        java.util.Deque<Node> queue = new java.util.ArrayDeque<>();
        queue.add(root);
        int cursor = 1;
        while (!queue.isEmpty() && cursor < level.length) {
            Node node = queue.poll();
            if (cursor < level.length) {
                if (level[cursor] != -1) {
                    node.left = new Node(level[cursor]);
                    queue.add(node.left);
                }
                cursor++;
            }
            if (cursor < level.length) {
                if (level[cursor] != -1) {
                    node.right = new Node(level[cursor]);
                    queue.add(node.right);
                }
                cursor++;
            }
        }
        java.util.List<Integer> out = new java.util.ArrayList<>();
        walk(root, out);
        int[] result = new int[out.size()];
        for (int i = 0; i < result.length; i++) result[i] = out.get(i);
        return result;
    }

    private void walk(Node node, java.util.List<Integer> out) {
        if (node == null) return;
        walk(node.left, out);
        out.add(node.value);
        walk(node.right, out);
    }
}''',
    py=py_inorder,
    cases=[
        [[1, -1, 2, 3]],
        [[]],
        [[1]],
        [[1, 2, 3, 4, 5, -1, 6]],
        [[1, -1, 2]],
    ],
    practice=[0, 2],
)

# ---------------------------------------------------------------- HARD

add(
    title="寻找两个正序数组的中位数",
    summary="实现 findMedianSortedArrays，返回两个有序数组合并后的中位数（保留 5 位小数）。",
    desc=(
        "给定两个大小分别为 m 和 n 的正序（升序）数组 nums1 和 nums2，"
        "返回这两个正序数组的中位数。\n\n"
        "返回类型为字符串：中位数保留 5 位小数，不足补零，例如 \"2.00000\"、\"2.50000\"。"
        "（本平台函数返回值不支持下浮点类型，用定点字符串表示。）\n\n"
        "数据范围：0 ≤ m, n ≤ 1000，1 ≤ m + n，-10^6 ≤ 元素 ≤ 10^6。"
    ),
    input_format="函数签名：findMedianSortedArrays(nums1: int[], nums2: int[]) → string。两个参数均为升序数组。",
    output_format="返回 string：中位数，格式为保留 5 位小数的十进制字符串（如 \"2.50000\"）。",
    examples=[
        {"input": "nums1 = [1,3], nums2 = [2]", "output": '"2.00000"', "explanation": "合并后为 [1,2,3]，中位数是 2。"},
        {"input": "nums1 = [1,2], nums2 = [3,4]", "output": '"2.50000"', "explanation": "合并后为 [1,2,3,4]，中位数是 (2+3)/2 = 2.5。"},
    ],
    tags=["数组", "二分查找", "分治"],
    fn="findMedianSortedArrays", params=["int[]", "int[]"], ret="string", time=2000, mem=262144,
    java='''class Solution {
    public String findMedianSortedArrays(int[] nums1, int[] nums2) {
        int[] merged = new int[nums1.length + nums2.length];
        int i = 0, j = 0, k = 0;
        while (i < nums1.length && j < nums2.length) {
            merged[k++] = nums1[i] <= nums2[j] ? nums1[i++] : nums2[j++];
        }
        while (i < nums1.length) merged[k++] = nums1[i++];
        while (j < nums2.length) merged[k++] = nums2[j++];
        int n = merged.length;
        double median = n % 2 == 1
                ? merged[n / 2]
                : (merged[n / 2 - 1] + (double) merged[n / 2]) / 2.0;
        return String.format(java.util.Locale.ROOT, "%.5f", median);
    }
}''',
    py=py_median,
    cases=[
        [[1, 3], [2]],
        [[1, 2], [3, 4]],
        [[], [1]],
        [[0, 0], [0, 0]],
        [[1, 3, 5, 7], [2, 4, 6, 8]],
        [[1, 2, 3, 4, 5], []],
    ],
    practice=[0, 1],
)

add(
    title="最长有效括号",
    summary="实现 longestValidParentheses，求最长有效括号子串的长度。",
    desc=(
        "给定一个只包含 '(' 和 ')' 的字符串 s，找出其中最长有效（格式正确且连续）括号子串的长度。\n\n"
        "子串必须连续，不能跨过未匹配的括号。\n\n"
        "数据范围：0 ≤ s.length ≤ 3 * 10^4。"
    ),
    input_format="函数签名：longestValidParentheses(s: string) → int。参数 s 为括号串，可为空。",
    output_format="返回 int：最长有效括号子串的长度；没有有效括号时返回 0。",
    examples=[
        {"input": 's = "(()"', "output": "2", "explanation": "最长有效括号子串是 ()。"},
        {"input": 's = ")()())"', "output": "4", "explanation": "最长有效括号子串是 ()()。"},
    ],
    tags=["字符串", "栈", "动态规划"],
    fn="longestValidParentheses", params=["string"], ret="int", time=2000, mem=262144,
    java='''class Solution {
    public int longestValidParentheses(String s) {
        java.util.Deque<Integer> stack = new java.util.ArrayDeque<>();
        stack.push(-1);
        int best = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '(') {
                stack.push(i);
            } else {
                stack.pop();
                if (stack.isEmpty()) stack.push(i);
                else best = Math.max(best, i - stack.peek());
            }
        }
        return best;
    }
}''',
    py=py_longest_valid,
    cases=[["(()"], [")()())"], [""], ["()(()"], ["()(())"], ["()()"]],
    practice=[0, 1],
)

add(
    title="接雨水",
    summary="实现 trap，计算柱状图能接住多少雨水。",
    desc=(
        "给定 n 个非负整数表示每个宽度为 1 的柱子的高度图，计算按此排列的柱子，"
        "下雨之后能接多少雨水。\n\n"
        "每根柱子上方能存的水量取决于它左右两侧最高柱子中较矮的一个。\n\n"
        "数据范围：0 ≤ height.length ≤ 2 * 10^4，0 ≤ height[i] ≤ 10^5。"
    ),
    input_format="函数签名：trap(height: int[]) → int。参数 height 为柱子高度数组，可为空。",
    output_format="返回 int：能接住的雨水总量。",
    examples=[
        {"input": "height = [0,1,0,2,1,0,1,3,2,1,2,1]", "output": "6", "explanation": "中间低洼处共可存 6 个单位雨水。"},
        {"input": "height = [4,2,0,3,2,5]", "output": "9", "explanation": "5 个位置分别存 2、4、1、2，合计 9。"},
    ],
    tags=["数组", "双指针", "单调栈"],
    fn="trap", params=["int[]"], ret="int", time=2000, mem=262144,
    java='''class Solution {
    public int trap(int[] height) {
        int left = 0, right = height.length - 1;
        int leftMax = 0, rightMax = 0, total = 0;
        while (left < right) {
            if (height[left] < height[right]) {
                leftMax = Math.max(leftMax, height[left]);
                total += leftMax - height[left];
                left++;
            } else {
                rightMax = Math.max(rightMax, height[right]);
                total += rightMax - height[right];
                right--;
            }
        }
        return total;
    }
}''',
    py=py_trap,
    cases=[
        [[0, 1, 0, 2, 1, 0, 1, 3, 2, 1, 2, 1]],
        [[4, 2, 0, 3, 2, 5]],
        [[1, 0, 1]],
        [[]],
        [[3, 0, 0, 0, 3]],
    ],
    practice=[0, 1],
)

add(
    title="编辑距离",
    summary="实现 minDistance，求把 word1 变成 word2 的最少操作数。",
    desc=(
        "给你两个单词 word1 和 word2，请返回将 word1 转换成 word2 所使用的最少操作数。\n\n"
        "你可以对一个单词进行三种操作：插入一个字符、删除一个字符、替换一个字符。\n\n"
        "数据范围：0 ≤ word1.length, word2.length ≤ 500，单词由小写英文字母组成。"
    ),
    input_format="函数签名：minDistance(word1: string, word2: string) → int。两个参数均可是空串。",
    output_format="返回 int：最少编辑次数。",
    examples=[
        {"input": 'word1 = "horse", word2 = "ros"', "output": "3", "explanation": "horse → rorse（替换 h）→ rose（删除 r）→ ros（删除 e）。"},
        {"input": 'word1 = "", word2 = "abc"', "output": "3", "explanation": "空串变成 abc 需要插入 3 个字符。"},
    ],
    tags=["字符串", "动态规划"],
    fn="minDistance", params=["string", "string"], ret="int", time=2000, mem=262144,
    java='''class Solution {
    public int minDistance(String word1, String word2) {
        int m = word1.length(), n = word2.length();
        int[] dp = new int[n + 1];
        for (int j = 0; j <= n; j++) dp[j] = j;
        for (int i = 1; i <= m; i++) {
            int prev = dp[0];
            dp[0] = i;
            for (int j = 1; j <= n; j++) {
                int current = dp[j];
                if (word1.charAt(i - 1) == word2.charAt(j - 1)) {
                    dp[j] = prev;
                } else {
                    dp[j] = 1 + Math.min(prev, Math.min(dp[j], dp[j - 1]));
                }
                prev = current;
            }
        }
        return dp[n];
    }
}''',
    py=py_min_distance,
    cases=[["horse", "ros"], ["intention", "execution"], ["", "abc"], ["abc", ""], ["same", "same"], ["a", "b"]],
    practice=[0, 1],
)

add(
    title="正则表达式匹配",
    summary="实现 isMatch，支持 '.' 和 '*' 的正则匹配。",
    desc=(
        "给你一个字符串 s 和一个字符规律 p，请你实现一个支持 '.' 和 '*' 的正则表达式匹配。\n\n"
        "规则：'.' 匹配任意单个字符；'*' 匹配零个或多个前面的那一个元素。"
        "匹配必须覆盖整个字符串 s，而不是部分字符串。\n\n"
        "数据范围：0 ≤ s.length ≤ 20，0 ≤ p.length ≤ 20，s 只含小写字母，"
        "p 只含小写字母以及字符 '.' 和 '*'，且 '*' 不会出现在开头。"
    ),
    input_format="函数签名：isMatch(s: string, p: string) → bool。s 为待匹配串，p 为正则模式。",
    output_format="返回 bool：整个 s 能被 p 完全匹配返回 true，否则 false。",
    examples=[
        {"input": 's = "aa", p = "a*"', "output": "true", "explanation": "a* 表示零个或多个 a，可以匹配 aa。"},
        {"input": 's = "mississippi", p = "mis*is*p*."', "output": "false", "explanation": "该模式无法完整匹配 mississippi。"},
    ],
    tags=["字符串", "动态规划", "递归"],
    fn="isMatch", params=["string", "string"], ret="bool", time=2000, mem=262144,
    java='''class Solution {
    public boolean isMatch(String s, String p) {
        int m = s.length(), n = p.length();
        boolean[][] dp = new boolean[m + 1][n + 1];
        dp[0][0] = true;
        for (int j = 2; j <= n; j++) {
            if (p.charAt(j - 1) == '*') dp[0][j] = dp[0][j - 2];
        }
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                char pc = p.charAt(j - 1);
                if (pc == '*') {
                    dp[i][j] = dp[i][j - 2];
                    char prev = p.charAt(j - 2);
                    if (prev == '.' || prev == s.charAt(i - 1)) {
                        dp[i][j] = dp[i][j] || dp[i - 1][j];
                    }
                } else if (pc == '.' || pc == s.charAt(i - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                }
            }
        }
        return dp[m][n];
    }
}''',
    py=py_is_match,
    cases=[
        ["aa", "a"],
        ["aa", "a*"],
        ["ab", ".*"],
        ["aab", "c*a*b"],
        ["mississippi", "mis*is*p*."],
        ["", "a*"],
        ["", "."],
    ],
    practice=[0, 1],
)


def render_expected(value):
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"))


def build():
    problems = []
    for item in PROBLEMS:
        cases = []
        for args in item["cases"]:
            expected = item["py"](*args)
            cases.append({
                "input": json.dumps(args, ensure_ascii=False, separators=(",", ":")),
                "expectedOutput": render_expected(expected),
            })
        practice = [cases[i] for i in item["practice"]]
        problems.append({
            "title": item["title"],
            "summary": item["summary"],
            "description": item["desc"],
            "inputFormat": item["input_format"],
            "outputFormat": item["output_format"],
            "examples": item["examples"],
            "difficulty": DIFFICULTY[item["title"]],
            "tags": item["tags"],
            "timeLimitMs": item["time"],
            "memoryLimitKb": item["mem"],
            "allowedLanguages": ["java"],
            "judgeMode": "FUNCTION",
            "functionName": item["fn"],
            "parameterTypes": item["params"],
            "returnType": item["ret"],
            "assessmentTestCases": cases,
            "practiceTestCases": practice,
        })
        item["_java"] = item["java"]
    payload = {"problems": problems}
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    with open(OUT, "w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2)
    return payload


if __name__ == "__main__":
    data = build()
    counts = {}
    for problem in data["problems"]:
        counts[problem["difficulty"]] = counts.get(problem["difficulty"], 0) + 1
    print("generated", len(data["problems"]), "problems ->", os.path.abspath(OUT))
    print("difficulty:", counts)
    for problem in data["problems"][:3]:
        print(" e.g.", problem["title"], problem["assessmentTestCases"][0])
