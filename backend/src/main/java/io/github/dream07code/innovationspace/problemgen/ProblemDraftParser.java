package io.github.dream07code.innovationspace.problemgen;

import io.github.dream07code.innovationspace.assessment.FunctionHarness;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 把模型返回的 JSON 解析成 {@link ProblemDraft}，并做结构校验。
 *
 * <p>模型输出不可信：可能带 Markdown 围栏、少字段、给出平台不支持的类型，
 * 或把参数写成「一个 JSON 对象」而不是参数数组。这些都在这里拦下来，
 * 只抛出 {@link InvalidDraft}，由调用方决定是重试还是直接失败。
 */
public final class ProblemDraftParser {
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MAX_TEST_CASES = 12;
  private static final int MIN_TEST_CASES = 4;

  private ProblemDraftParser() {}

  public static ProblemDraft parse(String content) {
    var node = readObject(content);
    String title = text(node, "title", 1, 120);
    String summary = text(node, "summary", 1, 300);
    String description = text(node, "description", 1, 20000);
    String inputFormat = text(node, "inputFormat", 1, 5000);
    String outputFormat = text(node, "outputFormat", 1, 5000);
    String functionName = text(node, "functionName", 1, 64);
    String returnType = text(node, "returnType", 1, 16);
    var parameterTypes = types(node.get("parameterTypes"));
    var signature = signature(functionName, parameterTypes, returnType);
    int timeLimitMs = integer(node, "timeLimitMs", 100, 30000);
    int memoryLimitKb = integer(node, "memoryLimitKb", 1024, 1048576);
    String referenceCode = text(node, "referenceCode", 1, 200000);
    var examples = examples(node.get("examples"));
    var tags = tags(node.get("tags"));
    var testCases = testCases(node.get("testCases"), signature);
    return new ProblemDraft(
        title,
        summary,
        description,
        inputFormat,
        outputFormat,
        examples,
        tags,
        functionName,
        parameterTypes,
        returnType,
        timeLimitMs,
        memoryLimitKb,
        referenceCode,
        testCases);
  }

  /** 生成题目的函数签名：与落库、判题使用同一份解析，避免两处口径不一致。 */
  public static FunctionHarness.Signature signature(
      String functionName, List<String> parameterTypes, String returnType) {
    return FunctionHarness.signature(functionName, parameterTypes, returnType);
  }

  private static JsonNode readObject(String content) {
    if (content == null || content.isBlank()) throw new InvalidDraft("模型返回空内容");
    String text = content.strip();
    if (text.startsWith("```")) {
      int firstLineEnd = text.indexOf('\n');
      if (firstLineEnd > 0) text = text.substring(firstLineEnd + 1).strip();
      if (text.endsWith("```")) text = text.substring(0, text.length() - 3).strip();
    }
    int start = text.indexOf('{');
    int end = text.lastIndexOf('}');
    if (start < 0 || end <= start) throw new InvalidDraft("模型返回的不是 JSON 对象");
    JsonNode node;
    try {
      node = JSON.readTree(text.substring(start, end + 1));
    } catch (RuntimeException error) {
      throw new InvalidDraft("模型返回的 JSON 无法解析");
    }
    if (node == null || !node.isObject()) throw new InvalidDraft("模型返回的不是 JSON 对象");
    return node;
  }

  private static String text(JsonNode node, String field, int min, int max) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual()) throw new InvalidDraft(field + " 缺失或不是字符串");
    String text = value.asText().strip();
    if (text.length() < min || text.length() > max)
      throw new InvalidDraft(field + " 长度不合规");
    return text;
  }

  private static int integer(JsonNode node, String field, int min, int max) {
    JsonNode value = node.get(field);
    if (value == null || !value.isIntegralNumber()) throw new InvalidDraft(field + " 缺失或不是整数");
    int number = value.asInt();
    if (number < min || number > max) throw new InvalidDraft(field + " 超出允许范围");
    return number;
  }

  private static List<String> types(JsonNode node) {
    if (node == null || !node.isArray() || node.isEmpty())
      throw new InvalidDraft("parameterTypes 缺失或为空");
    if (node.size() > 4) throw new InvalidDraft("parameterTypes 超过 4 个参数");
    var result = new ArrayList<String>(node.size());
    for (JsonNode item : node) {
      if (!item.isTextual()) throw new InvalidDraft("parameterTypes 含非字符串");
      String raw = item.asText();
      try {
        result.add(FunctionHarness.ValueType.parse(raw).wireName());
      } catch (IllegalArgumentException error) {
        throw new InvalidDraft("不支持的类型：" + raw);
      }
    }
    return result;
  }

  private static List<Map<String, Object>> examples(JsonNode node) {
    if (node == null || !node.isArray()) throw new InvalidDraft("examples 缺失");
    if (node.size() < 2 || node.size() > 20) throw new InvalidDraft("examples 需要 2-20 个");
    var result = new ArrayList<Map<String, Object>>(node.size());
    for (JsonNode item : node) {
      if (!item.isObject()) throw new InvalidDraft("examples 含非对象");
      var example = new LinkedHashMap<String, Object>();
      example.put("input", exampleText(item, "input"));
      example.put("output", exampleText(item, "output"));
      JsonNode explanation = item.get("explanation");
      if (explanation != null && explanation.isTextual() && !explanation.asText().isBlank()) {
        String text = explanation.asText().strip();
        if (text.length() > 1000) throw new InvalidDraft("examples.explanation 过长");
        example.put("explanation", text);
      }
      result.add(example);
    }
    return result;
  }

  private static String exampleText(JsonNode example, String field) {
    JsonNode value = example.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank())
      throw new InvalidDraft("examples." + field + " 缺失");
    String text = value.asText().strip();
    if (text.length() > 5000) throw new InvalidDraft("examples." + field + " 过长");
    return text;
  }

  private static List<String> tags(JsonNode node) {
    if (node == null || node.isNull()) return List.of();
    if (!node.isArray() || node.size() > 20) throw new InvalidDraft("tags 不合规");
    var result = new ArrayList<String>(node.size());
    for (JsonNode item : node) {
      if (!item.isTextual()) throw new InvalidDraft("tags 含非字符串");
      String tag = item.asText().strip();
      if (tag.isEmpty() || tag.length() > 40) throw new InvalidDraft("tags 长度不合规");
      result.add(tag);
    }
    return result;
  }

  private static List<ProblemDraft.TestCase> testCases(
      JsonNode node, FunctionHarness.Signature signature) {
    if (node == null || !node.isArray())
      throw new InvalidDraft("testCases 缺失");
    if (node.size() < MIN_TEST_CASES || node.size() > MAX_TEST_CASES)
      throw new InvalidDraft("testCases 需要 " + MIN_TEST_CASES + "-" + MAX_TEST_CASES + " 个");
    var result = new ArrayList<ProblemDraft.TestCase>(node.size());
    int boundary = 0;
    for (int index = 0; index < node.size(); index++) {
      JsonNode item = node.get(index);
      if (!item.isObject()) throw new InvalidDraft("testCases 含非对象");
      JsonNode inputNode = item.get("input");
      JsonNode expectedNode = item.get("expectedOutput");
      if (inputNode == null || !inputNode.isTextual())
        throw new InvalidDraft("testCases[" + index + "].input 缺失");
      if (expectedNode == null || !expectedNode.isTextual())
        throw new InvalidDraft("testCases[" + index + "].expectedOutput 缺失");
      String input = inputNode.asText().strip();
      String expected = expectedNode.asText().strip();
      if (input.isEmpty() || expected.isEmpty()) throw new InvalidDraft("testCases 有空值");
      try {
        FunctionHarness.parseArguments(signature, input);
      } catch (IllegalArgumentException error) {
        throw new InvalidDraft(
            "testCases[" + (index + 1) + "].input 与函数签名不匹配：" + error.getMessage());
      }
      checkExpected(index, expected, signature.returns());
      boolean isBoundary =
          item.get("boundary") != null && item.get("boundary").isBoolean()
              && item.get("boundary").asBoolean();
      if (isBoundary) boundary++;
      result.add(new ProblemDraft.TestCase(input, expected, isBoundary));
    }
    if (boundary < 1) throw new InvalidDraft("testCases 缺少边界用例");
    if (node.size() - boundary < 3) throw new InvalidDraft("testCases 常规用例少于 3 个");
    return result;
  }

  /** 期望输出必须是返回类型对应的 JSON，否则判题时会因为格式不同而永远不通过。 */
  private static void checkExpected(int index, String raw, FunctionHarness.ValueType type) {
    JsonNode node;
    try {
      node = JSON.readTree(raw);
    } catch (RuntimeException error) {
      throw new InvalidDraft("testCases[" + (index + 1) + "].expectedOutput 不是合法 JSON");
    }
    if (node == null || node.isNull())
      throw new InvalidDraft("testCases[" + (index + 1) + "].expectedOutput 为空");
    boolean ok =
        switch (type) {
          case INT -> node.isIntegralNumber() && node.asLong() >= Integer.MIN_VALUE
              && node.asLong() <= Integer.MAX_VALUE;
          case LONG -> node.isIntegralNumber();
          case BOOL -> node.isBoolean();
          case STRING -> node.isTextual();
          case INT_ARRAY -> allNumbers(node, true);
          case LONG_ARRAY -> allNumbers(node, false);
          case STRING_ARRAY -> allText(node);
          case INT_MATRIX -> matrix(node);
        };
    if (!ok)
      throw new InvalidDraft(
          "testCases[" + (index + 1) + "].expectedOutput 与返回类型 " + type.wireName() + " 不一致");
  }

  private static boolean allNumbers(JsonNode node, boolean intOnly) {
    if (!node.isArray()) return false;
    for (JsonNode item : node) {
      if (!item.isIntegralNumber()) return false;
      if (intOnly && (item.asLong() < Integer.MIN_VALUE || item.asLong() > Integer.MAX_VALUE))
        return false;
    }
    return true;
  }

  private static boolean allText(JsonNode node) {
    if (!node.isArray()) return false;
    for (JsonNode item : node) if (!item.isTextual()) return false;
    return true;
  }

  private static boolean matrix(JsonNode node) {
    if (!node.isArray()) return false;
    for (JsonNode row : node) if (!allNumbers(row, true)) return false;
    return true;
  }

  /** 模型输出不合规。消息只用于日志与重试提示，不直接展示给用户。 */
  public static final class InvalidDraft extends RuntimeException {
    public InvalidDraft(String message) {
      super(message);
    }
  }
}
