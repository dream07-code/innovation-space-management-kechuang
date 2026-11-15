/**
 * 力扣 Top 100 题单（前端写死，不调接口）。
 *
 * 每行的内容都对应一道真实力扣题：
 *   - title/difficulty/category 是题单展示用的题目信息；
 *   - topic 是「AI 出题」的知识点，pattern 是题型，两者一起预填生成弹窗；
 *   - problemId 有值表示这道题已经在题库里（已收录），点进去直接做题；
 *     为空表示题库还没有，点「AI 生成」按该题的难度与知识点现场生成一道。
 *
 * 题库里 30 条题目中有 4 条是「两数之和」的练习副本，按题目去重后
 * 题单命中 26 道，其余 74 道标为 AI 生成。
 */
import type { AssessmentProblem } from '@/api/types'

export type Top100Difficulty = Extract<AssessmentProblem['difficulty'], 'EASY' | 'MEDIUM' | 'HARD'>

export interface Top100Problem {
  /** 题单序号，1..100。 */
  no: number
  title: string
  difficulty: Top100Difficulty
  /** 分类，用于题单分组与筛选（哈希、双指针、二叉树…）。 */
  category: string
  /** AI 出题的知识点。 */
  topic: string
  /** AI 出题的题型（数组、字符串、链表、树…）。 */
  pattern: string
  /** 已收录时对应题库里的题目 id。 */
  problemId?: string
}

export const TOP100_PROBLEMS: Top100Problem[] = [
  { no: 1, title: '两数之和', difficulty: 'EASY', category: '哈希', topic: '哈希表', pattern: '数组', problemId: '1' },
  { no: 2, title: '字母异位词分组', difficulty: 'MEDIUM', category: '哈希', topic: '哈希表', pattern: '字符串', problemId: '18' },
  { no: 3, title: '最长连续序列', difficulty: 'MEDIUM', category: '哈希', topic: '哈希表', pattern: '数组' },
  { no: 4, title: '移动零', difficulty: 'EASY', category: '双指针', topic: '双指针', pattern: '数组' },
  { no: 5, title: '盛最多水的容器', difficulty: 'MEDIUM', category: '双指针', topic: '双指针', pattern: '数组' },
  { no: 6, title: '三数之和', difficulty: 'MEDIUM', category: '双指针', topic: '双指针', pattern: '数组', problemId: '14' },
  { no: 7, title: '接雨水', difficulty: 'HARD', category: '双指针', topic: '双指针', pattern: '数组', problemId: '28' },
  { no: 8, title: '无重复字符的最长子串', difficulty: 'MEDIUM', category: '滑动窗口', topic: '滑动窗口', pattern: '字符串', problemId: '12' },
  { no: 9, title: '找到字符串中所有字母异位词', difficulty: 'MEDIUM', category: '滑动窗口', topic: '滑动窗口', pattern: '字符串' },
  { no: 10, title: '和为 K 的子数组', difficulty: 'MEDIUM', category: '子串', topic: '前缀和', pattern: '数组' },
  { no: 11, title: '滑动窗口最大值', difficulty: 'HARD', category: '子串', topic: '单调队列', pattern: '数组' },
  { no: 12, title: '反转字符串', difficulty: 'EASY', category: '双指针', topic: '双指针', pattern: '字符串', problemId: '7' },
  { no: 13, title: '最大子数组和', difficulty: 'MEDIUM', category: '数组', topic: '动态规划', pattern: '数组', problemId: '11' },
  { no: 14, title: '合并区间', difficulty: 'MEDIUM', category: '数组', topic: '排序', pattern: '数组', problemId: '17' },
  { no: 15, title: '轮转数组', difficulty: 'MEDIUM', category: '数组', topic: '数组', pattern: '数组' },
  { no: 16, title: '除自身以外数组的乘积', difficulty: 'MEDIUM', category: '数组', topic: '前缀积', pattern: '数组' },
  { no: 17, title: '缺失的第一个正数', difficulty: 'HARD', category: '数组', topic: '数组', pattern: '数组' },
  { no: 18, title: '矩阵置零', difficulty: 'MEDIUM', category: '矩阵', topic: '矩阵', pattern: '数组' },
  { no: 19, title: '螺旋矩阵', difficulty: 'MEDIUM', category: '矩阵', topic: '矩阵', pattern: '数组' },
  { no: 20, title: '旋转图像', difficulty: 'MEDIUM', category: '矩阵', topic: '矩阵', pattern: '数组', problemId: '19' },
  { no: 21, title: '搜索二维矩阵 II', difficulty: 'MEDIUM', category: '矩阵', topic: '二分查找', pattern: '数组' },
  { no: 22, title: '相交链表', difficulty: 'EASY', category: '链表', topic: '链表', pattern: '链表' },
  { no: 23, title: '反转链表', difficulty: 'EASY', category: '链表', topic: '链表', pattern: '链表' },
  { no: 24, title: '回文链表', difficulty: 'EASY', category: '链表', topic: '链表', pattern: '链表' },
  { no: 25, title: '环形链表', difficulty: 'EASY', category: '链表', topic: '链表', pattern: '链表' },
  { no: 26, title: '环形链表 II', difficulty: 'MEDIUM', category: '链表', topic: '链表', pattern: '链表' },
  { no: 27, title: '合并两个有序链表', difficulty: 'EASY', category: '链表', topic: '链表', pattern: '链表', problemId: '10' },
  { no: 28, title: '两数相加', difficulty: 'MEDIUM', category: '链表', topic: '链表', pattern: '链表' },
  { no: 29, title: '删除链表的倒数第 N 个结点', difficulty: 'MEDIUM', category: '链表', topic: '链表', pattern: '链表', problemId: '16' },
  { no: 30, title: '两两交换链表中的节点', difficulty: 'MEDIUM', category: '链表', topic: '链表', pattern: '链表' },
  { no: 31, title: 'K 个一组翻转链表', difficulty: 'HARD', category: '链表', topic: '链表', pattern: '链表' },
  { no: 32, title: '随机链表的复制', difficulty: 'MEDIUM', category: '链表', topic: '链表', pattern: '链表' },
  { no: 33, title: '排序链表', difficulty: 'MEDIUM', category: '链表', topic: '链表', pattern: '链表' },
  { no: 34, title: '合并 K 个升序链表', difficulty: 'HARD', category: '堆', topic: '堆', pattern: '链表' },
  { no: 35, title: 'LRU 缓存', difficulty: 'MEDIUM', category: '设计', topic: '哈希表', pattern: '设计' },
  { no: 36, title: '二叉树的中序遍历', difficulty: 'EASY', category: '二叉树', topic: '二叉树', pattern: '树', problemId: '25' },
  { no: 37, title: '二叉树的最大深度', difficulty: 'EASY', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 38, title: '翻转二叉树', difficulty: 'EASY', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 39, title: '对称二叉树', difficulty: 'EASY', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 40, title: '二叉树的直径', difficulty: 'EASY', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 41, title: '二叉树的层序遍历', difficulty: 'MEDIUM', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 42, title: '将有序数组转换为二叉搜索树', difficulty: 'EASY', category: '二叉树', topic: '二叉搜索树', pattern: '树' },
  { no: 43, title: '验证二叉搜索树', difficulty: 'MEDIUM', category: '二叉树', topic: '二叉搜索树', pattern: '树' },
  { no: 44, title: '二叉搜索树中第 K 小的元素', difficulty: 'MEDIUM', category: '二叉树', topic: '二叉搜索树', pattern: '树' },
  { no: 45, title: '二叉树的右视图', difficulty: 'MEDIUM', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 46, title: '二叉树展开为链表', difficulty: 'MEDIUM', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 47, title: '从前序与中序遍历序列构造二叉树', difficulty: 'MEDIUM', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 48, title: '路径总和 III', difficulty: 'MEDIUM', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 49, title: '二叉树的最近公共祖先', difficulty: 'MEDIUM', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 50, title: '二叉树中的最大路径和', difficulty: 'HARD', category: '二叉树', topic: '二叉树', pattern: '树' },
  { no: 51, title: '岛屿数量', difficulty: 'MEDIUM', category: '图论', topic: '深度优先搜索', pattern: '数组' },
  { no: 52, title: '腐烂的橘子', difficulty: 'MEDIUM', category: '图论', topic: '广度优先搜索', pattern: '数组' },
  { no: 53, title: '课程表', difficulty: 'MEDIUM', category: '图论', topic: '拓扑排序', pattern: '图' },
  { no: 54, title: '实现 Trie (前缀树)', difficulty: 'MEDIUM', category: '设计', topic: '前缀树', pattern: '字符串' },
  { no: 55, title: '全排列', difficulty: 'MEDIUM', category: '回溯', topic: '回溯', pattern: '数组', problemId: '21' },
  { no: 56, title: '子集', difficulty: 'MEDIUM', category: '回溯', topic: '回溯', pattern: '数组', problemId: '20' },
  { no: 57, title: '电话号码的字母组合', difficulty: 'MEDIUM', category: '回溯', topic: '回溯', pattern: '字符串', problemId: '15' },
  { no: 58, title: '组合总和', difficulty: 'MEDIUM', category: '回溯', topic: '回溯', pattern: '数组' },
  { no: 59, title: '括号生成', difficulty: 'MEDIUM', category: '回溯', topic: '回溯', pattern: '字符串' },
  { no: 60, title: '单词搜索', difficulty: 'MEDIUM', category: '回溯', topic: '回溯', pattern: '数组' },
  { no: 61, title: '分割回文串', difficulty: 'MEDIUM', category: '回溯', topic: '回溯', pattern: '字符串' },
  { no: 62, title: 'N 皇后', difficulty: 'HARD', category: '回溯', topic: '回溯', pattern: '数组' },
  { no: 63, title: '搜索插入位置', difficulty: 'EASY', category: '二分查找', topic: '二分查找', pattern: '数组' },
  { no: 64, title: '搜索二维矩阵', difficulty: 'MEDIUM', category: '二分查找', topic: '二分查找', pattern: '数组' },
  { no: 65, title: '在排序数组中查找元素的第一个和最后一个位置', difficulty: 'MEDIUM', category: '二分查找', topic: '二分查找', pattern: '数组' },
  { no: 66, title: '搜索旋转排序数组', difficulty: 'MEDIUM', category: '二分查找', topic: '二分查找', pattern: '数组' },
  { no: 67, title: '寻找旋转排序数组中的最小值', difficulty: 'MEDIUM', category: '二分查找', topic: '二分查找', pattern: '数组' },
  { no: 68, title: '寻找两个正序数组的中位数', difficulty: 'HARD', category: '二分查找', topic: '二分查找', pattern: '数组', problemId: '26' },
  { no: 69, title: '有效的括号', difficulty: 'EASY', category: '栈', topic: '栈', pattern: '字符串', problemId: '9' },
  { no: 70, title: '最小栈', difficulty: 'MEDIUM', category: '栈', topic: '栈', pattern: '设计' },
  { no: 71, title: '字符串解码', difficulty: 'MEDIUM', category: '栈', topic: '栈', pattern: '字符串' },
  { no: 72, title: '每日温度', difficulty: 'MEDIUM', category: '栈', topic: '单调栈', pattern: '数组' },
  { no: 73, title: '柱状图中最大的矩形', difficulty: 'HARD', category: '栈', topic: '单调栈', pattern: '数组' },
  { no: 74, title: '数组中的第K个最大元素', difficulty: 'MEDIUM', category: '堆', topic: '堆', pattern: '数组' },
  { no: 75, title: '前 K 个高频元素', difficulty: 'MEDIUM', category: '堆', topic: '堆', pattern: '数组' },
  { no: 76, title: '数据流的中位数', difficulty: 'HARD', category: '堆', topic: '堆', pattern: '设计' },
  { no: 77, title: '买卖股票的最佳时机', difficulty: 'EASY', category: '贪心', topic: '贪心', pattern: '数组' },
  { no: 78, title: '跳跃游戏', difficulty: 'MEDIUM', category: '贪心', topic: '贪心', pattern: '数组', problemId: '22' },
  { no: 79, title: '跳跃游戏 II', difficulty: 'MEDIUM', category: '贪心', topic: '贪心', pattern: '数组' },
  { no: 80, title: '划分字母区间', difficulty: 'MEDIUM', category: '贪心', topic: '贪心', pattern: '字符串' },
  { no: 81, title: '爬楼梯', difficulty: 'EASY', category: '动态规划', topic: '动态规划', pattern: '数学' },
  { no: 82, title: '回文数', difficulty: 'EASY', category: '数学', topic: '数学', pattern: '数学', problemId: '8' },
  { no: 83, title: '打家劫舍', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '数组' },
  { no: 84, title: '计数质数', difficulty: 'MEDIUM', category: '数学', topic: '筛法', pattern: '数学', problemId: '6' },
  { no: 85, title: '零钱兑换', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '数组' },
  { no: 86, title: '单词拆分', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '字符串' },
  { no: 87, title: '最长递增子序列', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '数组', problemId: '24' },
  { no: 88, title: '乘积最大子数组', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '数组' },
  { no: 89, title: '分割等和子集', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '数组' },
  { no: 90, title: '最长有效括号', difficulty: 'HARD', category: '动态规划', topic: '动态规划', pattern: '字符串', problemId: '27' },
  { no: 91, title: '不同路径', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '数组', problemId: '23' },
  { no: 92, title: '正则表达式匹配', difficulty: 'HARD', category: '动态规划', topic: '动态规划', pattern: '字符串', problemId: '30' },
  { no: 93, title: '最长回文子串', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '字符串', problemId: '13' },
  { no: 94, title: '最长公共子序列', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '字符串' },
  { no: 95, title: '编辑距离', difficulty: 'MEDIUM', category: '动态规划', topic: '动态规划', pattern: '字符串', problemId: '29' },
  { no: 96, title: '只出现一次的数字', difficulty: 'EASY', category: '位运算', topic: '位运算', pattern: '数组' },
  { no: 97, title: '多数元素', difficulty: 'EASY', category: '技巧', topic: '哈希表', pattern: '数组' },
  { no: 98, title: '颜色分类', difficulty: 'MEDIUM', category: '技巧', topic: '双指针', pattern: '数组' },
  { no: 99, title: '下一个排列', difficulty: 'MEDIUM', category: '技巧', topic: '数组', pattern: '数组' },
  { no: 100, title: '寻找重复数', difficulty: 'MEDIUM', category: '技巧', topic: '快慢指针', pattern: '数组' },
]

/** 题单分类，按出现顺序去重，用于筛选下拉。 */
export const TOP100_CATEGORIES: string[] = [
  ...new Set(TOP100_PROBLEMS.map((item) => item.category)),
]

export const TOP100_COLLECTED_COUNT = TOP100_PROBLEMS.filter((item) => item.problemId).length
