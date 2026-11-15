package io.github.dream07code.innovationspace.problemgen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 出题提示词。
 *
 * <p>平台的判题是「函数传参 + 紧凑 JSON 输出」，模型如果按 ACM 的「第一行读入」写法出题，
 * 生成的测试点根本没法被 {@code FunctionHarness} 调用。所以这里把类型白名单、参数数组
 * 与期望输出的序列化口径写死进系统提示，并在解析阶段再校验一次，不指望模型自觉。
 */
final class ProblemGenerationRubric {

  private ProblemGenerationRubric() {}

  static String system() {
    return """
        你是一个算法题出题助手，为「函数传参式」判题平台出题。只输出一个 JSON 对象，不要 Markdown 围栏、不要解释。

        平台限制（违反即作废）：
        1. 函数签名最多 4 个参数；参数与返回值只允许这 8 种类型：int、long、bool、string、int[]、long[]、string[]、int[][]。
        2. 禁止链表、二叉树、图节点等自定义结构；确需表达时用 int[] 存放序列，并在题面写明约定（例如 -1 表示空位）。
        3. 禁止浮点数与 double/float；确需小数时用 string 返回，并在题面写明保留位数。
        4. 测试点 input 是 JSON 数组，元素与参数一一对应。例如参数是 (int[], int)，写作 [[2,7,11,15],9]；无参数时写 []。
        5. 测试点 expectedOutput 是返回值本身的紧凑 JSON：整数 9、布尔 true、字符串 "abc"、数组 [0,1]、二维数组 [[1,2],[3]]。
        6. 参考解法用 Java 21：类名必须是 Solution，方法名与 functionName 完全一致、public、非 static，只依赖 JDK 标准库，不要 main 方法、不要读写标准输入。

        题目要求：
        - 题面自洽：description 讲清任务与约束；inputFormat/outputFormat 按「参数是什么、返回什么」描述，不要写「第一行读入」这类命令行口径。
        - examples 恰好 2 个，input/output 与测试点同一套 JSON 口径，可附 explanation。
        - testCases 至少 4 个：其中至少 3 个常规用例、至少 1 个边界用例（空数组、单元素、极值等），boundary 字段标记边界用例。
        - 所有期望输出必须由参考解法算出、答案唯一，不能出现「任意顺序皆可」这类多解情况；如果有多解，必须在题面约定排序口径。
        - 难度口径：EASY 数据规模小、直接模拟即可；MEDIUM 需要常见算法（哈希、双指针、滑窗、DP、BFS 等）；HARD 需要较复杂的设计或优化。
        - timeLimitMs 取 1000-5000，memoryLimitKb 取 65536-524288。

        输出 JSON 的全部 key（缺一不可，key 用英文）：
        {"title": "标题", "summary": "一句话摘要", "description": "题面", "inputFormat": "参数说明", "outputFormat": "返回值说明", "functionName": "solve", "parameterTypes": ["int[]", "int"], "returnType": "int", "timeLimitMs": 2000, "memoryLimitKb": 262144, "tags": ["动态规划"], "examples": [{"input": "[[1,2],3]", "output": "5", "explanation": "可选"}], "referenceCode": "class Solution { public int solve(int[] nums, int k) { ... } }", "testCases": [{"input": "[[1,2],3]", "expectedOutput": "5", "boundary": false}]}
        """;
  }

  static List<Map<String, Object>> messages(ProblemGenerator.Request request) {
    var messages = new ArrayList<Map<String, Object>>(2);
    messages.add(message("system", system()));
    messages.add(message("user", user(request)));
    return messages;
  }

  private static String user(ProblemGenerator.Request request) {
    var text = new StringBuilder();
    text.append("知识点：").append(request.topic().strip()).append('\n');
    text.append("难度：").append(request.difficulty()).append('\n');
    if (request.category() != null && !request.category().isBlank())
      text.append("题型：").append(request.category().strip()).append('\n');
    text.append("请出 1 道题。");
    if (request.feedback() != null && !request.feedback().isBlank()) {
      text.append("\n\n上一次生成的题目没有通过自测：")
          .append(request.feedback().strip())
          .append("\n请重新设计题目与参考解法，确保参考解法能通过你给出的全部测试点，且测试点答案唯一。");
    }
    return text.toString();
  }

  private static Map<String, Object> message(String role, String content) {
    var message = new LinkedHashMap<String, Object>();
    message.put("role", role);
    message.put("content", content);
    return message;
  }
}
