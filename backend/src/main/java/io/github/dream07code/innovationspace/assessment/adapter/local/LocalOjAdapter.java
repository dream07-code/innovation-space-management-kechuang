package io.github.dream07code.innovationspace.assessment.adapter.local;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.assessment.adapter.OjAdapter;
import io.github.dream07code.innovationspace.assessment.adapter.judge0.Judge0OjProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 本地开发用判题器：直接调用本机编译工具链编译并执行提交的代码。
 *
 * <p>与 Judge0 的差别：没有容器隔离，只靠进程级超时、输出上限和独立临时工作目录约束。
 * 因此只能用于本地开发和演示（{@code OJ_PROVIDER=local}），不能作为生产判题后端。
 *
 * <p>依赖本机已安装的工具链：{@code g++}（C++17）、{@code javac}/{@code java}（JDK 17+）、
 * {@code python3}、{@code node}。缺少对应工具时该语言返回 INTERNAL_ERROR 而不是误判。
 */
public class LocalOjAdapter implements OjAdapter {
  private static final Set<String> SUPPORTED_LANGUAGES =
      Set.of("cpp", "java", "python", "javascript");
  private static final Set<String> MEMORY_LIMITED_LANGUAGES = Set.of("cpp", "python", "javascript");
  private static final int COMPILE_TIMEOUT_MS = 30_000;
  private static final int MAX_ERROR_BYTES = 8 * 1024;
  private static final int POLL_INTERVAL_MS = 5;
  private static final int DRAIN_TIMEOUT_MS = 3_000;
  /** 落库的 safeError 上限比字段上限（500）略小，留出省略号与提示语的空间。 */
  private static final int MAX_SAFE_ERROR = 460;

  private final int timeLimitMs;
  private final int memoryLimitKb;
  private final int maxOutputBytes;

  public LocalOjAdapter(Judge0OjProperties properties) {
    // 题目自身的 time_limit_ms 未进入 OjSubmission，本地判题统一使用 app.oj.cpu-time-limit-seconds
    this.timeLimitMs = Math.max(500, properties.getCpuTimeLimitSeconds() * 1000);
    this.memoryLimitKb = Math.max(1024, properties.getMemoryLimitKb());
    this.maxOutputBytes = Math.max(1024, properties.getMaxResponseBytes());
  }

  @Override
  public OjJudgeResult judge(OjSubmission submission) {
    List<OjSubmission.TestCase> testCases = submission.testCases();
    if (!SUPPORTED_LANGUAGES.contains(submission.language()))
      return single(
          testCases.size(),
          OjVerdict.INTERNAL_ERROR,
          "本地判题器不支持该语言："
              + submission.language()
              + "（支持 cpp / java / python / javascript）");
    if (testCases.isEmpty())
      return new OjJudgeResult(OjVerdict.ACCEPTED, 0, 0, 0, 0, null, List.of());

    Path work;
    try {
      work = Files.createTempDirectory("oj-local-");
    } catch (IOException error) {
      return single(testCases.size(), OjVerdict.INTERNAL_ERROR, "本地判题器无法创建临时目录");
    }
    // 题目的 time_limit_ms 优先；没有配置时回退到 app.oj.cpu-time-limit-seconds
    int limit = submission.timeLimitMs() > 0 ? submission.timeLimitMs() : timeLimitMs;
    try {
      String compileError = compile(submission.language(), submission.code(), work);
      if (compileError != null)
        return single(testCases.size(), OjVerdict.COMPILATION_ERROR, compileError);
      return executeAll(submission.language(), testCases, work, limit);
    } finally {
      deleteQuietly(work);
    }
  }

  private OjJudgeResult executeAll(
      String language, List<OjSubmission.TestCase> testCases, Path work, int limitMs) {
    var results = new ArrayList<OjJudgeResult.TestCaseResult>();
    int passed = 0;
    int maxTimeMs = 0;
    int maxMemoryKb = 0;
    OjVerdict verdict = OjVerdict.ACCEPTED;
    String safeError = null;
    for (int index = 0; index < testCases.size(); index++) {
      var outcome = run(language, testCases.get(index), work, limitMs);
      results.add(
          new OjJudgeResult.TestCaseResult(
              index + 1,
              outcome.verdict(),
              outcome.timeMs(),
              outcome.memoryKb(),
              outcome.safeError(),
              outcome.actualOutput()));
      maxTimeMs = Math.max(maxTimeMs, outcome.timeMs());
      maxMemoryKb = Math.max(maxMemoryKb, outcome.memoryKb());
      if (outcome.verdict() == OjVerdict.ACCEPTED) {
        passed++;
      } else if (verdict == OjVerdict.ACCEPTED) {
        verdict = outcome.verdict();
        safeError = outcome.safeError();
      }
    }
    return new OjJudgeResult(verdict, passed, testCases.size(), maxTimeMs, maxMemoryKb, safeError, results);
  }

  private Outcome run(
      String language, OjSubmission.TestCase testCase, Path work, int limitMs) {
    Process process;
    try {
      var builder =
          new ProcessBuilder(executeCommand(language, work))
              .directory(work.toFile())
              .redirectErrorStream(false);
      var environment = builder.environment();
      // 清空宿主环境，避免判题进程读到部署机的配置或凭据
      environment.clear();
      environment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
      environment.put("LANG", "C.UTF-8");
      environment.put("LC_ALL", "C.UTF-8");
      environment.put("HOME", work.toString());
      environment.put("TMPDIR", work.toString());
      process = builder.start();
    } catch (IOException error) {
      return new Outcome(
          OjVerdict.INTERNAL_ERROR, 0, 0, "本地判题器无法启动运行时：" + error.getMessage(), null);
    }

    var stdout = new Capture();
    var stderr = new Capture();
    var stdoutThread = reader(process.getInputStream(), maxOutputBytes + 1, stdout);
    var stderrThread = reader(process.getErrorStream(), MAX_ERROR_BYTES, stderr);
    var stdinThread = writer(process.getOutputStream(), testCase.input());

    long startedAt = System.nanoTime();
    long deadline = startedAt + limitMs * 1_000_000L;
    int peakKb = 0;
    boolean timedOut = false;
    try {
      while (true) {
        // 先采样再等待：短命进程（十几毫秒）只有靠高频采样才抓得到真实峰值
        peakKb = Math.max(peakKb, peakMemoryKb(process.pid()));
        if (process.waitFor(POLL_INTERVAL_MS, TimeUnit.MILLISECONDS)) break;
        if (System.nanoTime() >= deadline) {
          timedOut = true;
          process.destroyForcibly();
          process.waitFor(DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS);
          break;
        }
      }
      int elapsedMs = (int) Math.max(0, (System.nanoTime() - startedAt) / 1_000_000L);
      peakKb = Math.max(peakKb, peakMemoryKb(process.pid()));
      stdoutThread.join(DRAIN_TIMEOUT_MS);
      stderrThread.join(DRAIN_TIMEOUT_MS);
      stdinThread.interrupt();

      if (timedOut)
        return new Outcome(
            OjVerdict.TIME_LIMIT_EXCEEDED,
            Math.max(elapsedMs, limitMs),
            peakKb,
            "运行超时（超过 " + limitMs + " ms）",
            null);
      if (stdout.truncated)
        return new Outcome(
            OjVerdict.WRONG_ANSWER,
            elapsedMs,
            peakKb,
            "输出超过 " + maxOutputBytes + " 字节上限",
            clip(normalize(stdout.value), 200));
      if (process.exitValue() != 0)
        return new Outcome(
            OjVerdict.RUNTIME_ERROR,
            elapsedMs,
            peakKb,
            runtimeMessage(process.exitValue(), stderr.value),
            clip(normalize(stdout.value), 200));
      if (MEMORY_LIMITED_LANGUAGES.contains(language) && peakKb > memoryLimitKb)
        return new Outcome(
            OjVerdict.MEMORY_LIMIT_EXCEEDED,
            elapsedMs,
            peakKb,
            "内存超限（峰值 " + peakKb + " KB，上限 " + memoryLimitKb + " KB）",
            null);
      if (normalize(stdout.value).equals(normalize(testCase.expectedOutput())))
        return new Outcome(
            OjVerdict.ACCEPTED, elapsedMs, peakKb, null, clip(normalize(stdout.value), 2000));
      // 实际输出按判题口径规范化后再回传，避免前端表格里出现成排空行
      return new Outcome(
          OjVerdict.WRONG_ANSWER,
          elapsedMs,
          peakKb,
          "输出与预期不一致",
          clip(normalize(stdout.value), 2000));
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return new Outcome(OjVerdict.INTERNAL_ERROR, 0, 0, "判题被中断", null);
    }
  }

  /** 返回 null 表示编译通过，否则返回可直接展示给学生的编译错误。 */
  private String compile(String language, String code, Path work) {
    try {
      Path source = work.resolve(sourceFileName(language));
      Files.writeString(source, code, StandardCharsets.UTF_8);
      List<String> command =
          switch (language) {
            case "cpp" ->
                List.of(
                    "g++",
                    "-std=c++17",
                    "-O2",
                    "-pipe",
                    "-o",
                    work.resolve("main").toString(),
                    source.toString());
            case "java" -> {
              Files.createDirectories(work.resolve("classes"));
              yield List.of(
                  "javac",
                  "-encoding",
                  "UTF-8",
                  "-d",
                  work.resolve("classes").toString(),
                  source.toString());
            }
            case "python" ->
                List.of(
                    "python3",
                    "-I",
                    "-c",
                    // 用内置 compile 做语法检查：只输出“文件:行号: 原因”，不刷一屏 traceback
                    "import sys\n"
                        + "src = open(sys.argv[1], encoding='utf-8').read()\n"
                        + "try:\n"
                        + "    compile(src, 'main.py', 'exec')\n"
                        + "except SyntaxError as err:\n"
                        + "    print(f'main.py:{err.lineno}: {err.msg}')\n"
                        + "    sys.exit(1)\n",
                    source.toString());
            default -> List.of("node", "--check", source.toString());
          };
      return runCompile(command, work);
    } catch (IOException error) {
      return "本地判题器无法写入源码文件：" + error.getMessage();
    }
  }

  private String runCompile(List<String> command, Path work) {
    Process process;
    try {
      var builder =
          new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true);
      var environment = builder.environment();
      environment.clear();
      environment.put("PATH", "/usr/local/bin:/usr/bin:/bin");
      environment.put("LANG", "C.UTF-8");
      environment.put("LC_ALL", "C.UTF-8");
      process = builder.start();
    } catch (IOException error) {
      return "本地判题器无法启动 " + command.getFirst() + "，请确认本机已安装：" + error.getMessage();
    }
    var output = new Capture();
    var reader = reader(process.getInputStream(), MAX_ERROR_BYTES * 4, output);
    try {
      if (!process.waitFor(COMPILE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        return "编译超时（超过 " + COMPILE_TIMEOUT_MS / 1000 + " 秒）";
      }
      reader.join(DRAIN_TIMEOUT_MS);
      if (process.exitValue() == 0) return null;
      String message = sanitize(output.value, work);
      return message.isBlank()
          ? "编译失败（编译器退出码 " + process.exitValue() + "）"
          : message;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
      return "编译被中断";
    }
  }

  private List<String> executeCommand(String language, Path work) {
    return switch (language) {
      case "java" ->
          List.of(
              "java",
              "-Xmx" + javaHeapKb() + "k",
              "-XX:+UseSerialGC",
              "-Dfile.encoding=UTF-8",
              "-cp",
              work.resolve("classes").toString(),
              "Main");
      case "cpp" -> List.of(work.resolve("main").toString());
      case "python" -> List.of("python3", "-I", work.resolve("main.py").toString());
      default -> List.of("node", work.resolve("main.js").toString());
    };
  }

  private static String sourceFileName(String language) {
    return switch (language) {
      case "cpp" -> "main.cpp";
      case "java" -> "Main.java";
      case "python" -> "main.py";
      default -> "main.js";
    };
  }

  /**
   * Java 的堆上限留给 JVM 自身一部分余量：-Xmx 取内存限制减 16MB，
   * 避免启动时就把配额吃掉导致 OOM 被误判成运行错误。
   */
  private int javaHeapKb() {
    return Math.max(16 * 1024, memoryLimitKb - 16 * 1024);
  }

  private static String runtimeMessage(int exitCode, String stderr) {
    String detail = sanitize(stderr == null ? "" : stderr.strip(), null);
    if (detail.isBlank()) return "运行时错误（退出码 " + exitCode + "）";
    return clip("运行时错误（退出码 " + exitCode + "）：" + detail, MAX_SAFE_ERROR);
  }

  /** 去掉临时目录等宿主路径，避免把判题机目录结构暴露给学生。 */
  private static String sanitize(String text, Path work) {
    if (text == null) return "";
    String cleaned = text;
    if (work != null) {
      // 先去掉“目录/”，再去掉裸目录，避免留下 “/main.cpp” 这样的前导斜杠
      cleaned =
          cleaned
              .replace(work.toAbsolutePath() + "/", "")
              .replace(work + "/", "")
              .replace(work.toAbsolutePath().toString(), "")
              .replace(work.toString(), "");
    }
    cleaned = cleaned.replaceAll("/tmp/oj-local-[A-Za-z0-9]+/?", "");
    cleaned = cleaned.strip();
    return cleaned.length() <= MAX_SAFE_ERROR
        ? cleaned
        : cleaned.substring(0, MAX_SAFE_ERROR) + "…";
  }

  /** OJ 常规比较：逐行忽略行尾空白与行尾空行，其余字符必须完全一致。 */
  static String normalize(String text) {
    if (text == null) return "";
    String unified = text.replace("\r\n", "\n").replace('\r', '\n');
    String joined =
        Arrays.stream(unified.split("\n", -1))
            .map(line -> line.replaceAll("\\s+$", ""))
            .collect(Collectors.joining("\n"));
    return joined.replaceAll("\n+$", "");
  }

  private static String clip(String text, int limit) {
    if (text == null) return null;
    return text.length() <= limit ? text : text.substring(0, limit);
  }

  private static int peakMemoryKb(long pid) {
    Path status = Path.of("/proc", Long.toString(pid), "status");
    try {
      for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
        if (line.startsWith("VmHWM:")) {
          String digits = line.replaceAll("[^0-9]", "");
          return digits.isEmpty() ? 0 : Integer.parseInt(digits);
        }
      }
    } catch (IOException | NumberFormatException ignored) {
      // 进程已退出或非 Linux 平台：拿不到峰值就记 0
    }
    return 0;
  }

  private static OjJudgeResult single(int total, OjVerdict verdict, String safeError) {
    var results = new ArrayList<OjJudgeResult.TestCaseResult>();
    for (int index = 1; index <= total; index++)
      results.add(new OjJudgeResult.TestCaseResult(index, verdict, 0, 0, safeError));
    return new OjJudgeResult(verdict, 0, total, 0, 0, safeError, results);
  }

  private static void deleteQuietly(Path root) {
    try (var paths = Files.walk(root)) {
      paths.sorted(Comparator.reverseOrder()).forEach(LocalOjAdapter::deleteOne);
    } catch (IOException ignored) {
      // 临时目录清理失败不影响判题结果
    }
  }

  private static void deleteOne(Path path) {
    try {
      Files.deleteIfExists(path);
    } catch (IOException ignored) {
      // 忽略单个文件删除失败
    }
  }

  /** 线程读取流并把结果写回 Capture；调用方负责 join，避免管道写满阻塞子进程。 */
  private static Thread reader(InputStream stream, int limit, Capture capture) {
    Thread thread =
        new Thread(
            () -> {
              byte[] buffer = new byte[8192];
              var collected = new java.io.ByteArrayOutputStream();
              int total = 0;
              try (stream) {
                int read;
                while ((read = stream.read(buffer)) != -1) {
                  if (total < limit) {
                    int remain = limit - total;
                    int take = Math.min(read, remain);
                    collected.write(buffer, 0, take);
                    total += take;
                  }
                  if (total >= limit) capture.truncated = true;
                }
              } catch (IOException ignored) {
                // 进程被强杀时读取会抛异常，保留已读内容即可
              }
              capture.value = collected.toString(StandardCharsets.UTF_8);
            },
            "oj-local-reader");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static Thread writer(OutputStream stream, String input) {
    Thread thread =
        new Thread(
            () -> {
              try (stream) {
                stream.write(input.getBytes(StandardCharsets.UTF_8));
                stream.flush();
              } catch (IOException ignored) {
                // 程序不读输入或提前退出时写入会失败，属于正常情况
              }
            },
            "oj-local-writer");
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  private static final class Capture {
    private volatile String value = "";
    private volatile boolean truncated = false;
  }

  private record Outcome(
      OjVerdict verdict, int timeMs, int memoryKb, String safeError, String actualOutput) {}
}
