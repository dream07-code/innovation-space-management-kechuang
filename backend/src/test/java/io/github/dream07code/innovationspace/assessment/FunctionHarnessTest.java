package io.github.dream07code.innovationspace.assessment;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** 函数式判题调用桩：生成结果要在本地能真实编译运行。 */
class FunctionHarnessTest {
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @Test
  void runsFibonacciWithEveryToolchainAvailableLocally(@TempDir Path dir) throws Exception {
    var signature = FunctionHarness.signature("fib", List.of("long"), "long");
    var arguments = FunctionHarness.parseArguments(signature, "[10]");

    assertExecutable(dir, "cpp", cppSolution(), signature, arguments, "main.cpp", "g++", "main");
    assertExecutable(dir, "java", javaSolution(), signature, arguments, "Main.java", "javac", null);
    assertExecutable(dir, "python", pythonSolution(), signature, arguments, "main.py", "python3", null);
    assertExecutable(dir, "javascript", jsSolution(), signature, arguments, "main.js", "node", null);
  }

  @Test
  void generatesGoAndRustEntrypointsEvenWithoutLocalToolchains() {
    var signature = FunctionHarness.signature("fib", List.of("long"), "long");
    var arguments = FunctionHarness.parseArguments(signature, "[10]");

    String go = FunctionHarness.wrap("go", signature, arguments, "func fib(n int64) int64 { return 0 }");
    assertTrue(go.startsWith("package main"));
    assertTrue(go.contains("result := fib(10)"));
    assertTrue(go.contains("encoder.Encode(result)"));

    String rust =
        FunctionHarness.wrap(
            "rust",
            signature,
            arguments,
            "struct Solution;\n\nimpl Solution {\n    pub fn fib(n: i64) -> i64 { 0 }\n}");
    assertTrue(rust.contains("Solution::fib(10i64)"));
    assertTrue(rust.contains("print!(\"{}\", result);"));
  }

  @Test
  void normalizesStringExpectations() {
    assertEquals("\"abc\"", FunctionHarness.canonicalExpected("abc", FunctionHarness.ValueType.STRING));
    assertEquals("\"abc\"", FunctionHarness.canonicalExpected("\"abc\"", FunctionHarness.ValueType.STRING));
    assertEquals("55", FunctionHarness.canonicalExpected("55", FunctionHarness.ValueType.LONG));
  }

  @Test
  void rejectsMismatchedArguments() {
    var signature = FunctionHarness.signature("twoSum", List.of("int[]", "int"), "int[]");
    assertEquals("[2,7,11,15]", FunctionHarness.canonicalExpected("[2, 7, 11, 15]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> FunctionHarness.parseArguments(signature, "[[2,7],9,1]"));
    assertThrows(
        IllegalArgumentException.class,
        () -> FunctionHarness.parseArguments(signature, "[\"2,7\",9]"));
    assertThrows(
        IllegalArgumentException.class, () -> FunctionHarness.signature("fib", List.of("double"), "long"));
  }

  @Test
  void printsArraysAsCompactJson() {
    var signature = FunctionHarness.signature("twoSum", List.of("int[]", "int"), "int[]");
    var arguments = FunctionHarness.parseArguments(signature, "[[2,7,11,15],9]");
    String java = FunctionHarness.wrap("java", signature, arguments, "class Solution {}");
    assertTrue(java.contains("new int[]{2,7,11,15}"));
    assertTrue(java.contains("judgeJoin(judgeSolution.twoSum("));

    String cpp = FunctionHarness.wrap("cpp", signature, arguments, "class Solution {};");
    assertTrue(cpp.contains("vector<int>{2,7,11,15}"));
    assertTrue(cpp.contains("judge_join(judge_solution.twoSum("));
  }


  @Test
  void printsArrayReturnValuesAsCompactJson(@TempDir Path dir) throws Exception {
    var signature = FunctionHarness.signature("twoSum", List.of("int[]", "int"), "int[]");
    var arguments = FunctionHarness.parseArguments(signature, "[[2,7,11,15],9]");

    String java =
        """
        class Solution {
            public int[] twoSum(int[] nums, int target) {
                return new int[]{0, 1};
            }
        }
        """;
    assertExecutable(dir.resolve("array"), "java", java, signature, arguments, "Main.java", "javac", null, "[0,1]");

    String cpp =
        """
        class Solution {
        public:
            vector<int> twoSum(vector<int> nums, int target) {
                return vector<int>{0, 1};
            }
        };
        """;
    assertExecutable(dir.resolve("array-cpp"), "cpp", cpp, signature, arguments, "main.cpp", "g++", "main", "[0,1]");
  }

  private void assertExecutable(
      Path dir,
      String language,
      String userCode,
      FunctionHarness.Signature signature,
      tools.jackson.databind.JsonNode arguments,
      String fileName,
      String compileCommand,
      String compiledName)
      throws Exception {
    assertExecutable(dir, language, userCode, signature, arguments, fileName, compileCommand, compiledName, "55");
  }

  private void assertExecutable(
      Path dir,
      String language,
      String userCode,
      FunctionHarness.Signature signature,
      tools.jackson.databind.JsonNode arguments,
      String fileName,
      String compileCommand,
      String compiledName,
      String expected)
      throws Exception {
    String source = FunctionHarness.wrap(language, signature, arguments, userCode);
    Path work = Files.createDirectories(dir.resolve(language));
    Files.writeString(work.resolve(fileName), source, StandardCharsets.UTF_8);

    if ("g++".equals(compileCommand)) {
      run(work, "g++", "-std=c++17", "-O2", "-o", "main", fileName);
    } else if ("javac".equals(compileCommand)) {
      run(work, "javac", fileName);
    }

    String output =
        switch (language) {
          case "cpp" -> run(work, "./main");
          case "java" -> run(work, "java", "Main");
          case "python" -> run(work, "python3", fileName);
          case "javascript" -> run(work, "node", fileName);
          default -> throw new IllegalArgumentException(language);
        };
    assertEquals(expected, output.trim(), language + " harness output");
  }

  private String run(Path work, String... command) throws Exception {
    Assumptions.assumeTrue(toolExists(command[0]) || command[0].startsWith("./"), "missing " + command[0]);
    var process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start();
    String output;
    try (var stream = process.getInputStream()) {
      output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "timeout: " + String.join(" ", command));
    assertEquals(0, process.exitValue(), String.join(" ", command) + " -> " + output);
    return output;
  }

  private boolean toolExists(String tool) throws IOException, InterruptedException {
    var process = new ProcessBuilder("which", tool).redirectErrorStream(true).start();
    try (var stream = process.getInputStream()) {
      stream.readAllBytes();
    }
    return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
  }

  private static String cppSolution() {
    return """
        class Solution {
        public:
            long long fib(long long n) {
                long long a = 0, b = 1;
                for (long long i = 0; i < n; i++) { long long t = a + b; a = b; b = t; }
                return a;
            }
        };
        """;
  }

  private static String javaSolution() {
    return """
        class Solution {
            public long fib(long n) {
                long a = 0, b = 1;
                for (long i = 0; i < n; i++) { long t = a + b; a = b; b = t; }
                return a;
            }
        }
        """;
  }

  private static String pythonSolution() {
    return """
        class Solution:
            def fib(self, n):
                a, b = 0, 1
                for _ in range(n):
                    a, b = b, a + b
                return a
        """;
  }

  private static String jsSolution() {
    return """
        class Solution {
          fib(n) {
            let a = 0, b = 1
            for (let i = 0; i < n; i++) { const t = a + b; a = b; b = t }
            return a
          }
        }
        """;
  }
}
