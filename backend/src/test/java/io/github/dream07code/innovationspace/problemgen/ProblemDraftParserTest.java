package io.github.dream07code.innovationspace.problemgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * AI 出题输出的解析与校验。
 *
 * <p>模型输出不可信，这里钉死几条必须拒绝的形态：类型超出白名单、参数不是数组、
 * 期望输出与返回类型不一致、缺少边界用例。放进去就会变成「学生怎么做都判错」的题。
 */
class ProblemDraftParserTest {

  @Test
  void parsesValidDraft() {
    var draft = ProblemDraftParser.parse(validJson());

    assertEquals("最大子数组和", draft.title());
    assertEquals("maxSubArray", draft.functionName());
    assertEquals(java.util.List.of("int[]"), draft.parameterTypes());
    assertEquals("int", draft.returnType());
    assertEquals(4, draft.testCases().size());
    assertEquals(2, draft.examples().size());
    // 边界用例被标记：题目要求至少 1 个。
    assertTrue(draft.testCases().stream().anyMatch(ProblemDraft.TestCase::boundary));
  }

  @Test
  void stripsMarkdownFence() {
    var draft = ProblemDraftParser.parse("```json\n" + validJson() + "\n```");
    assertEquals("最大子数组和", draft.title());
  }

  @Test
  void rejectsUnsupportedType() {
    var error =
        assertThrows(
            ProblemDraftParser.InvalidDraft.class,
            () ->
                ProblemDraftParser.parse(
                    validJson().replace("\"parameterTypes\": [\"int[]\"]", "\"parameterTypes\": [\"ListNode\"]")));
    assertTrue(error.getMessage().contains("不支持的类型"), error.getMessage());
  }

  @Test
  void rejectsArgumentsNotMatchingSignature() {
    // 参数声明为 int[]，测试点却给了两个参数：判题时必然调用失败。
    var error =
        assertThrows(
            ProblemDraftParser.InvalidDraft.class,
            () -> ProblemDraftParser.parse(validJson().replace("[[-2,1,-3,4,-1,2,1,-5,4]]", "[1,2]")));
    assertTrue(error.getMessage().contains("与函数签名不匹配"), error.getMessage());
  }

  @Test
  void rejectsExpectedOutputTypeMismatch() {
    // 返回 int，期望输出给字符串：判题口径不一致，永远通过不了。
    var error =
        assertThrows(
            ProblemDraftParser.InvalidDraft.class,
            () ->
                ProblemDraftParser.parse(
                    validJson().replace("\"expectedOutput\": \"6\"", "\"expectedOutput\": \"\\\"six\\\"\"")));
    assertTrue(error.getMessage().contains("与返回类型"), error.getMessage());
  }

  @Test
  void rejectsDraftWithoutBoundaryCase() {
    var json = validJson().replace("\"boundary\": true", "\"boundary\": false");
    var error =
        assertThrows(ProblemDraftParser.InvalidDraft.class, () -> ProblemDraftParser.parse(json));
    assertTrue(error.getMessage().contains("边界用例"), error.getMessage());
  }

  @Test
  void rejectsTooFewTestCases() {
    // 只留 2 个测试点（1 常规 + 1 边界）：常规用例不足 3 个。
    String json = validJson();
    int start = json.indexOf("\"testCases\"");
    String truncated =
        json.substring(0, start)
            + "\"testCases\": ["
            + caseJson(2)
            + ","
            + caseJson(3)
            + "]}";
    var error =
        assertThrows(ProblemDraftParser.InvalidDraft.class, () -> ProblemDraftParser.parse(truncated));
    assertTrue(error.getMessage().contains("testCases"), error.getMessage());
  }

  private static String caseJson(int position) {
    return switch (position) {
      case 1 -> "{\"input\": \"[[-2,1,-3,4,-1,2,1,-5,4]]\", \"expectedOutput\": \"6\", \"boundary\": false}";
      case 2 -> "{\"input\": \"[[5,4,-1,7,8]]\", \"expectedOutput\": \"23\", \"boundary\": false}";
      default -> "{\"input\": \"[[-1]]\", \"expectedOutput\": \"-1\", \"boundary\": true}";
    };
  }

  private static String validJson() {
    return """
        {
          "title": "最大子数组和",
          "summary": "求连续子数组的最大和",
          "description": "给定整数数组 nums，找出和最大的连续子数组并返回其和。",
          "inputFormat": "参数 nums：整数数组，1 <= nums.length <= 100000。",
          "outputFormat": "返回一个整数，表示最大子数组和。",
          "functionName": "maxSubArray",
          "parameterTypes": ["int[]"],
          "returnType": "int",
          "timeLimitMs": 2000,
          "memoryLimitKb": 262144,
          "tags": ["动态规划", "数组"],
          "examples": [
            {"input": "[[-2,1,-3,4,-1,2,1,-5,4]]", "output": "6", "explanation": "连续子数组 [4,-1,2,1] 的和最大。"},
            {"input": "[[1]]", "output": "1"}
          ],
          "referenceCode": "class Solution { public int maxSubArray(int[] nums) { int best = nums[0], cur = nums[0]; for (int i = 1; i < nums.length; i++) { cur = Math.max(nums[i], cur + nums[i]); best = Math.max(best, cur); } return best; } }",
          "testCases": [
            {"input": "[[-2,1,-3,4,-1,2,1,-5,4]]", "expectedOutput": "6", "boundary": false},
            {"input": "[[1]]", "expectedOutput": "1", "boundary": false},
            {"input": "[[5,4,-1,7,8]]", "expectedOutput": "23", "boundary": false},
            {"input": "[[-1]]", "expectedOutput": "-1", "boundary": true}
          ]
        }
        """;
  }
}
