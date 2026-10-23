package io.github.dream07code.innovationspace.assessment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 函数式判题（力扣风格）的调用桩生成器。
 *
 * 题目声明「函数名 + 参数类型 + 返回类型」，测试点输入是一段 JSON 数组；
 * 判题时把参数以字面量形式嵌进入口代码，调用成员编写的函数并打印返回值（紧凑 JSON），
 * 再交给 Judge0 按标准输出比对。
 *
 * 支持的参数/返回类型刻意保持有限：int、long、bool、string、int[]、long[]、string[]、int[][]。
 * 这样每种语言都能用标准库生成字面量和打印语句，不需要额外的序列化依赖。
 */
public final class FunctionHarness {
  public static final Set<String> LANGUAGES = Set.of("cpp", "java", "python", "javascript", "go", "rust");
  private static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MAX_PARAMETERS = 4;
  private static final int MAX_ARRAY_LENGTH = 100_000;

  private FunctionHarness() {}

  public enum ValueType {
    INT("int"),
    LONG("long"),
    BOOL("bool"),
    STRING("string"),
    INT_ARRAY("int[]"),
    LONG_ARRAY("long[]"),
    STRING_ARRAY("string[]"),
    INT_MATRIX("int[][]");

    private final String wireName;

    ValueType(String wireName) {
      this.wireName = wireName;
    }

    public String wireName() {
      return wireName;
    }

    public static ValueType parse(String raw) {
      for (ValueType type : values()) if (type.wireName.equals(raw)) return type;
      throw new IllegalArgumentException("unsupported type: " + raw);
    }
  }

  public record Signature(String functionName, List<ValueType> parameters, ValueType returns) {
    public Signature {
      if (functionName == null || !functionName.matches("[A-Za-z_][A-Za-z0-9_]{0,63}"))
        throw new IllegalArgumentException("invalid function name");
      if (parameters == null || parameters.size() > MAX_PARAMETERS)
        throw new IllegalArgumentException("invalid parameter list");
      parameters = List.copyOf(parameters);
      if (returns == null) throw new IllegalArgumentException("return type is required");
    }

    public String call(String receiver, List<String> arguments) {
      return receiver + functionName + "(" + String.join(", ", arguments) + ")";
    }
  }

  public static Signature signature(
      String functionName, List<String> parameterTypes, String returnType) {
    var parameters = new ArrayList<ValueType>();
    for (String type : parameterTypes == null ? List.<String>of() : parameterTypes)
      parameters.add(ValueType.parse(type));
    return new Signature(functionName, parameters, ValueType.parse(returnType));
  }

  /** 校验测试点输入是参数个数、类型都匹配的 JSON 数组。 */
  public static JsonNode parseArguments(Signature signature, String raw) {
    JsonNode node;
    try {
      node = JSON.readTree(raw);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("arguments must be a JSON array");
    }
    if (node == null || !node.isArray() || node.size() != signature.parameters().size())
      throw new IllegalArgumentException("argument count mismatch");
    for (int index = 0; index < node.size(); index++)
      checkValue(signature.parameters().get(index), node.get(index));
    return node;
  }

  private static void checkValue(ValueType type, JsonNode value) {
    if (value == null) throw new IllegalArgumentException("argument is missing");
    switch (type) {
      case INT -> {
        if (!value.isIntegralNumber() || !fitsInt(value.asLong()))
          throw new IllegalArgumentException("expect int");
      }
      case LONG -> {
        if (!value.isIntegralNumber()) throw new IllegalArgumentException("expect long");
      }
      case BOOL -> {
        if (!value.isBoolean()) throw new IllegalArgumentException("expect bool");
      }
      case STRING -> {
        if (!value.isTextual()) throw new IllegalArgumentException("expect string");
      }
      case INT_ARRAY, LONG_ARRAY -> checkArray(value, type == ValueType.INT_ARRAY);
      case STRING_ARRAY -> {
        if (!value.isArray() || value.size() > MAX_ARRAY_LENGTH)
          throw new IllegalArgumentException("expect string array");
        for (JsonNode item : value)
          if (!item.isTextual()) throw new IllegalArgumentException("expect string array");
      }
      case INT_MATRIX -> {
        if (!value.isArray() || value.size() > MAX_ARRAY_LENGTH)
          throw new IllegalArgumentException("expect int matrix");
        for (JsonNode row : value) checkArray(row, true);
      }
    }
  }

  private static void checkArray(JsonNode value, boolean intOnly) {
    if (!value.isArray() || value.size() > MAX_ARRAY_LENGTH)
      throw new IllegalArgumentException("expect integer array");
    for (JsonNode item : value) {
      if (!item.isIntegralNumber()) throw new IllegalArgumentException("expect integer array");
      if (intOnly && !fitsInt(item.asLong())) throw new IllegalArgumentException("int out of range");
    }
  }

  private static boolean fitsInt(long value) {
    return value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
  }

  /**
   * 归一化期望输出；返回字符串类型时允许管理员直接写正文（不必手写引号）。
   */
  public static String canonicalExpected(String raw, ValueType returnType) {
    String canonical = canonicalExpected(raw);
    if (returnType == ValueType.STRING && !canonical.startsWith("\"")) {
      String text = raw == null ? "" : raw.trim();
      return JSON.writeValueAsString(text);
    }
    return canonical;
  }

  /** 归一化期望输出：能解析成 JSON 就压成紧凑 JSON，否则按原文比较。 */
  public static String canonicalExpected(String raw) {
    if (raw == null) return "";
    String trimmed = raw.trim();
    if (trimmed.isEmpty()) return "";
    try {
      return JSON.writeValueAsString(JSON.readTree(trimmed));
    } catch (RuntimeException error) {
      return trimmed;
    }
  }

  /** 生成最终提交代码：语言前缀 + 用户代码 + 调用桩。 */
  public static String wrap(
      String language, Signature signature, JsonNode arguments, String userCode) {
    if (!LANGUAGES.contains(language))
      throw new IllegalArgumentException("function mode does not support " + language);
    var args = new ArrayList<String>();
    for (int index = 0; index < signature.parameters().size(); index++)
      args.add(literal(language, signature.parameters().get(index), arguments.get(index)));
    return switch (language) {
      case "cpp" -> cpp(signature, args, userCode);
      case "java" -> java(signature, args, userCode);
      case "python" -> python(signature, args, userCode);
      case "javascript" -> javascript(signature, args, userCode);
      case "go" -> go(signature, args, userCode);
      case "rust" -> rust(signature, args, userCode);
      default -> throw new IllegalArgumentException("function mode does not support " + language);
    };
  }

  private static String cpp(Signature signature, List<String> args, String userCode) {
    String call = signature.call("judge_solution.", args);
    String print =
        switch (signature.returns()) {
          case INT, LONG -> call;
          case BOOL -> "(" + call + " ? \"true\" : \"false\")";
          case STRING -> "judge_quote(" + call + ")";
          case INT_ARRAY, LONG_ARRAY, STRING_ARRAY, INT_MATRIX -> "judge_join(" + call + ")";
        };
    return """
        #include <bits/stdc++.h>
        using namespace std;

        static string judge_quote(const string &value) {
          string out = "\\"";
          for (char c : value) {
            if (c == '"' || c == '\\\\') out += '\\\\';
            out += c;
          }
          return out + "\\"";
        }
        static string judge_join(const vector<int> &value) {
          string out = "[";
          for (size_t i = 0; i < value.size(); i++) { if (i) out += ","; out += to_string(value[i]); }
          return out + "]";
        }
        static string judge_join(const vector<long long> &value) {
          string out = "[";
          for (size_t i = 0; i < value.size(); i++) { if (i) out += ","; out += to_string(value[i]); }
          return out + "]";
        }
        static string judge_join(const vector<string> &value) {
          string out = "[";
          for (size_t i = 0; i < value.size(); i++) { if (i) out += ","; out += judge_quote(value[i]); }
          return out + "]";
        }
        static string judge_join(const vector<vector<int>> &value) {
          string out = "[";
          for (size_t i = 0; i < value.size(); i++) { if (i) out += ","; out += judge_join(value[i]); }
          return out + "]";
        }

        %s

        int main() {
          Solution judge_solution;
          cout << %s;
          return 0;
        }
        """
        .formatted(userCode, print);
  }

  private static String java(Signature signature, List<String> args, String userCode) {
    String call = signature.call("judgeSolution.", args);
    String print =
        switch (signature.returns()) {
          case INT, LONG -> "String.valueOf(" + call + ")";
          case BOOL -> "(" + call + " ? \"true\" : \"false\")";
          case STRING -> "judgeQuote(" + call + ")";
          case INT_ARRAY, LONG_ARRAY, STRING_ARRAY, INT_MATRIX -> "judgeJoin(" + call + ")";
        };
    return userCode
        + """

        public class Main {
            public static void main(String[] args) {
                Solution judgeSolution = new Solution();
                System.out.print(%s);
            }

            static String judgeQuote(String value) {
                StringBuilder out = new StringBuilder("\\"");
                for (char c : value.toCharArray()) {
                    if (c == '"' || c == '\\\\') out.append('\\\\');
                    out.append(c);
                }
                return out.append('"').toString();
            }

            static String judgeJoin(int[] value) {
                StringBuilder out = new StringBuilder("[");
                for (int i = 0; i < value.length; i++) { if (i > 0) out.append(','); out.append(value[i]); }
                return out.append(']').toString();
            }

            static String judgeJoin(long[] value) {
                StringBuilder out = new StringBuilder("[");
                for (int i = 0; i < value.length; i++) { if (i > 0) out.append(','); out.append(value[i]); }
                return out.append(']').toString();
            }

            static String judgeJoin(String[] value) {
                StringBuilder out = new StringBuilder("[");
                for (int i = 0; i < value.length; i++) { if (i > 0) out.append(','); out.append(judgeQuote(value[i])); }
                return out.append(']').toString();
            }

            static String judgeJoin(int[][] value) {
                StringBuilder out = new StringBuilder("[");
                for (int i = 0; i < value.length; i++) { if (i > 0) out.append(','); out.append(judgeJoin(value[i])); }
                return out.append(']').toString();
            }
        }
        """
            .formatted(print);
  }

  private static String python(Signature signature, List<String> args, String userCode) {
    String call = signature.call("Solution().", args);
    return "import json\n\n"
        + userCode
        + """

        if __name__ == "__main__":
            _judge_result = %s
            print(json.dumps(_judge_result, ensure_ascii=False, separators=(",", ":")))
        """
            .formatted(call);
  }

  private static String javascript(Signature signature, List<String> args, String userCode) {
    String call = signature.call("new Solution().", args);
    return userCode
        + """

        process.stdout.write(JSON.stringify(%s))
        """
            .formatted(call);
  }

  private static String go(Signature signature, List<String> args, String userCode) {
    String call = signature.call("", args);
    return """
        package main

        import (
        	"encoding/json"
        	"os"
        )

        %s

        func main() {
        	result := %s
        	encoder := json.NewEncoder(os.Stdout)
        	encoder.SetEscapeHTML(false)
        	if err := encoder.Encode(result); err != nil {
        		os.Exit(1)
        	}
        }
        """
        .formatted(userCode, call);
  }

  private static String rust(Signature signature, List<String> args, String userCode) {
    String call = signature.call("Solution::", args);
    String print =
        switch (signature.returns()) {
          case INT, LONG -> "print!(\"{}\", result);";
          case BOOL -> "print!(\"{}\", if result { \"true\" } else { \"false\" });";
          case STRING -> "print!(\"{:?}\", result);";
          case INT_ARRAY, LONG_ARRAY ->
              "print!(\"[{}]\", result.iter().map(|x| x.to_string()).collect::<Vec<_>>().join(\",\"));";
          case STRING_ARRAY ->
              "print!(\"[{}]\", result.iter().map(|x| format!(\"{:?}\", x)).collect::<Vec<_>>().join(\",\"));";
          case INT_MATRIX ->
              "print!(\"[{}]\", result.iter().map(|row| format!(\"[{}]\", row.iter().map(|x| x.to_string()).collect::<Vec<_>>().join(\",\"))).collect::<Vec<_>>().join(\",\"));";
        };
    return userCode
        + """

        fn main() {
            let result = %s;
            %s
        }
        """
            .formatted(call, print);
  }

  private static String literal(String language, ValueType type, JsonNode value) {
    return switch (language) {
      case "cpp" -> cppLiteral(type, value);
      case "java" -> javaLiteral(type, value);
      case "python" -> pythonLiteral(type, value);
      case "javascript" -> jsonLiteral(value);
      case "go" -> goLiteral(type, value);
      case "rust" -> rustLiteral(type, value);
      default -> throw new IllegalArgumentException("unsupported language");
    };
  }

  private static String cppLiteral(ValueType type, JsonNode value) {
    return switch (type) {
      case INT -> String.valueOf(value.asInt());
      case LONG -> value.asLong() + "LL";
      case BOOL -> value.asBoolean() ? "true" : "false";
      case STRING -> quote(value.asText(), '"', '\\', "cpp");
      case INT_ARRAY -> "vector<int>" + cppArray(value);
      case LONG_ARRAY -> "vector<long long>" + cppLongArray(value);
      case STRING_ARRAY -> "vector<string>" + cppStringArray(value);
      case INT_MATRIX -> "vector<vector<int>>" + cppMatrix(value);
    };
  }

  private static String cppArray(JsonNode value) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(String.valueOf(item.asInt()));
    return "{" + String.join(",", parts) + "}";
  }

  private static String cppLongArray(JsonNode value) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(item.asLong() + "LL");
    return "{" + String.join(",", parts) + "}";
  }

  private static String cppStringArray(JsonNode value) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(quote(item.asText(), '"', '\\', "cpp"));
    return "{" + String.join(",", parts) + "}";
  }

  private static String cppMatrix(JsonNode value) {
    var rows = new ArrayList<String>();
    for (JsonNode row : value) rows.add(cppArray(row));
    return "{" + String.join(",", rows) + "}";
  }

  private static String javaLiteral(ValueType type, JsonNode value) {
    return switch (type) {
      case INT -> String.valueOf(value.asInt());
      case LONG -> value.asLong() + "L";
      case BOOL -> value.asBoolean() ? "true" : "false";
      case STRING -> quote(value.asText(), '"', '\\', "java");
      case INT_ARRAY -> "new int[]" + javaIntArray(value, "");
      case LONG_ARRAY -> "new long[]" + javaLongArray(value);
      case STRING_ARRAY -> "new String[]" + javaStringArray(value);
      case INT_MATRIX -> "new int[][]" + javaMatrix(value);
    };
  }

  private static String javaIntArray(JsonNode value, String suffix) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(item.asInt() + suffix);
    return "{" + String.join(",", parts) + "}";
  }

  private static String javaLongArray(JsonNode value) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(item.asLong() + "L");
    return "{" + String.join(",", parts) + "}";
  }

  private static String javaStringArray(JsonNode value) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(quote(item.asText(), '"', '\\', "java"));
    return "{" + String.join(",", parts) + "}";
  }

  private static String javaMatrix(JsonNode value) {
    var rows = new ArrayList<String>();
    for (JsonNode row : value) rows.add(javaIntArray(row, ""));
    return "{" + String.join(",", rows) + "}";
  }

  private static String pythonLiteral(ValueType type, JsonNode value) {
    return switch (type) {
      case INT -> String.valueOf(value.asInt());
      case LONG -> String.valueOf(value.asLong());
      case BOOL -> value.asBoolean() ? "True" : "False";
      case STRING -> jsonLiteral(value);
      case INT_ARRAY, LONG_ARRAY -> jsonLiteral(value);
      case STRING_ARRAY -> jsonLiteral(value);
      case INT_MATRIX -> jsonLiteral(value);
    };
  }

  private static String goLiteral(ValueType type, JsonNode value) {
    return switch (type) {
      case INT -> String.valueOf(value.asInt());
      case LONG -> String.valueOf(value.asLong());
      case BOOL -> value.asBoolean() ? "true" : "false";
      case STRING -> quote(value.asText(), '"', '\\', "go");
      case INT_ARRAY -> "[]int" + goArray(value, false);
      case LONG_ARRAY -> "[]int64" + goArray(value, true);
      case STRING_ARRAY -> "[]string" + goStringArray(value);
      case INT_MATRIX -> "[][]int" + goMatrix(value);
    };
  }

  private static String goArray(JsonNode value, boolean int64) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(String.valueOf(int64 ? item.asLong() : item.asInt()));
    return "{" + String.join(",", parts) + "}";
  }

  private static String goStringArray(JsonNode value) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(quote(item.asText(), '"', '\\', "go"));
    return "{" + String.join(",", parts) + "}";
  }

  private static String goMatrix(JsonNode value) {
    var rows = new ArrayList<String>();
    for (JsonNode row : value) rows.add(goArray(row, false));
    return "{" + String.join(",", rows) + "}";
  }

  private static String rustLiteral(ValueType type, JsonNode value) {
    return switch (type) {
      case INT -> String.valueOf(value.asInt());
      case LONG -> value.asLong() + "i64";
      case BOOL -> value.asBoolean() ? "true" : "false";
      case STRING -> quote(value.asText(), '"', '\\', "rust") + ".to_string()";
      case INT_ARRAY -> "vec![" + rustNumbers(value) + "]";
      case LONG_ARRAY -> "vec![" + rustNumbers(value) + "]";
      case STRING_ARRAY -> "vec![" + rustStrings(value) + "]";
      case INT_MATRIX -> "vec![" + rustMatrix(value) + "]";
    };
  }

  private static String rustNumbers(JsonNode value) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(String.valueOf(item.asLong()));
    return String.join(",", parts);
  }

  private static String rustStrings(JsonNode value) {
    var parts = new ArrayList<String>();
    for (JsonNode item : value) parts.add(quote(item.asText(), '"', '\\', "rust") + ".to_string()");
    return String.join(",", parts);
  }

  private static String rustMatrix(JsonNode value) {
    var rows = new ArrayList<String>();
    for (JsonNode row : value) rows.add("vec![" + rustNumbers(row) + "]");
    return String.join(",", rows);
  }

  private static String jsonLiteral(JsonNode value) {
    return JSON.writeValueAsString(value);
  }

  /** 生成字符串字面量，按语言转义引号、反斜杠和换行。 */
  private static String quote(String value, char quote, char escape, String language) {
    var out = new StringBuilder().append(quote);
    for (char c : value.toCharArray()) {
      switch (c) {
        case '\n' -> out.append(escape).append('n');
        case '\r' -> out.append(escape).append('r');
        case '\t' -> out.append(escape).append('t');
        default -> {
          if (c == quote || c == escape) out.append(escape);
          out.append(c);
        }
      }
    }
    return out.append(quote).toString();
  }

  /** 响应/日志里使用的签名描述，便于排查。 */
  public static Map<String, Object> describe(Signature signature) {
    var out = new LinkedHashMap<String, Object>();
    out.put("functionName", signature.functionName());
    var params = new ArrayList<String>();
    for (ValueType type : signature.parameters()) params.add(type.wireName());
    out.put("parameterTypes", params);
    out.put("returnType", signature.returns().wireName());
    return out;
  }
}
