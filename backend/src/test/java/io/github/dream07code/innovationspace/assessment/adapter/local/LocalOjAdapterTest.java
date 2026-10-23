package io.github.dream07code.innovationspace.assessment.adapter.local;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dream07code.innovationspace.assessment.OjSubmission;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjProperties;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * 本地判题器的真实编译执行测试。
 *
 * <p>这类测试依赖本机工具链（g++ / javac / python3 / node）；缺少工具时整体跳过，
 * 保证在 CI 之类的精简环境里不会变成假失败。
 */
class LocalOjAdapterTest {
  private static final List<OjSubmission.TestCase> TWO_CASES =
      List.of(new OjSubmission.TestCase("2 3", "5"), new OjSubmission.TestCase("10 20", "30"));

  private static LocalOjAdapter adapter(int seconds) {
    var properties = new Judge0OjProperties();
    properties.setCpuTimeLimitSeconds(seconds);
    properties.setMemoryLimitKb(128000);
    properties.setMaxResponseBytes(65536);
    return new LocalOjAdapter(properties);
  }

  private static void assumeInstalled(String command) {
    Assumptions.assumeTrue(installed(command), command + " 未安装，跳过本地判题测试");
  }

  private static boolean installed(String command) {
    try {
      var process =
          new ProcessBuilder(command, "-version").redirectErrorStream(true).start();
      process.getInputStream().readAllBytes();
      return process.waitFor(10, TimeUnit.SECONDS);
    } catch (IOException error) {
      return false;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  @Test
  void pythonAcceptedWhenOutputMatches() {
    assumeInstalled("python3");
    var result =
        adapter(2)
            .judge(
                new OjSubmission(
                    "python",
                    "import sys\n"
                        + "a,b = map(int, sys.stdin.read().split())\n"
                        + "print(a+b)\n",
                    TWO_CASES));
    assertEquals(OjVerdict.ACCEPTED, result.verdict());
    assertEquals(2, result.passedTestCases());
    assertEquals(2, result.totalTestCases());
    assertNull(result.safeError());
    assertTrue(result.timeMs() >= 0);
    assertEquals(2, result.testCaseResults().size());
  }

  @Test
  void pythonWrongAnswerKeepsActualOutputForStudent() {
    assumeInstalled("python3");
    var result =
        adapter(2).judge(new OjSubmission("python", "print(1)\n", TWO_CASES));
    assertEquals(OjVerdict.WRONG_ANSWER, result.verdict());
    assertEquals(0, result.passedTestCases());
    assertEquals("1", result.testCaseResults().getFirst().actualOutput());
    assertNotNull(result.safeError());
  }

  @Test
  void pythonSyntaxErrorIsCompilationError() {
    assumeInstalled("python3");
    var result =
        adapter(2).judge(new OjSubmission("python", "def broken(:\n  pass\n", TWO_CASES));
    assertEquals(OjVerdict.COMPILATION_ERROR, result.verdict());
    assertEquals(0, result.passedTestCases());
    assertTrue(result.testCaseResults().stream()
        .allMatch(caseResult -> caseResult.verdict() == OjVerdict.COMPILATION_ERROR));
    // 编译错误必须能定位到代码位置，同时不能暴露判题机的临时目录
    assertTrue(result.safeError().contains("main.py:1"), result.safeError());
    assertTrue(!result.safeError().contains("/tmp/oj-local-"));
  }

  @Test
  void pythonRuntimeErrorIsReported() {
    assumeInstalled("python3");
    var result = adapter(2).judge(new OjSubmission("python", "raise SystemExit(3)\n", TWO_CASES));
    assertEquals(OjVerdict.RUNTIME_ERROR, result.verdict());
    assertEquals(0, result.passedTestCases());
  }

  @Test
  void pythonTimeoutIsTimeLimitExceeded() {
    assumeInstalled("python3");
    var result =
        adapter(1).judge(new OjSubmission("python", "while True:\n    pass\n", TWO_CASES));
    assertEquals(OjVerdict.TIME_LIMIT_EXCEEDED, result.verdict());
    assertTrue(result.timeMs() >= 900);
  }

  @Test
  void cppAcceptedWhenOutputMatches() {
    assumeInstalled("g++");
    var result =
        adapter(3)
            .judge(
                new OjSubmission(
                    "cpp",
                    "#include <bits/stdc++.h>\n"
                        + "int main(){int a,b;std::cin>>a>>b;std::cout<<a+b<<std::endl;}\n",
                    TWO_CASES));
    assertEquals(OjVerdict.ACCEPTED, result.verdict());
    assertEquals(2, result.passedTestCases());
  }

  @Test
  void cppCompileErrorIsReported() {
    assumeInstalled("g++");
    var result =
        adapter(3).judge(new OjSubmission("cpp", "int main(){ return not_a_symbol; }\n", TWO_CASES));
    assertEquals(OjVerdict.COMPILATION_ERROR, result.verdict());
    assertTrue(result.safeError().contains("main.cpp"));
  }

  @Test
  void javaAcceptedAndMainClassIsSupported() {
    assumeInstalled("javac");
    assumeInstalled("java");
    var result =
        adapter(5)
            .judge(
                new OjSubmission(
                    "java",
                    "import java.io.*;\n"
                        + "public class Main {\n"
                        + "  public static void main(String[] args) throws IOException {\n"
                        + "    BufferedReader in = new BufferedReader(new InputStreamReader(System.in));\n"
                        + "    String[] parts = in.readLine().trim().split(\"\\\\s+\");\n"
                        + "    System.out.println(Integer.parseInt(parts[0]) + Integer.parseInt(parts[1]));\n"
                        + "  }\n"
                        + "}\n",
                    TWO_CASES));
    assertEquals(OjVerdict.ACCEPTED, result.verdict());
    assertEquals(2, result.passedTestCases());
  }

  @Test
  void trailingWhitespaceIsIgnoredWhenComparingOutput() {
    assumeInstalled("python3");
    var result =
        adapter(2)
            .judge(new OjSubmission("python", "print('5   ')\nprint()\n", List.of(new OjSubmission.TestCase("", "5"))));
    assertEquals(OjVerdict.ACCEPTED, result.verdict());
  }

  @Test
  void unsupportedLanguageIsInternalErrorNotFakeVerdict() {
    var result = adapter(2).judge(new OjSubmission("go", "package main\nfunc main(){}\n", TWO_CASES));
    assertEquals(OjVerdict.INTERNAL_ERROR, result.verdict());
    assertEquals(0, result.passedTestCases());
  }
}
