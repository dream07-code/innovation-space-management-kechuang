package io.github.dream07code.innovationspace.assessment.adapter.judge0;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import io.github.dream07code.innovationspace.assessment.adapter.OjAdapter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class Judge0OjAdapter implements OjAdapter {
  private static final Logger log = LoggerFactory.getLogger(Judge0OjAdapter.class);
  private static final Map<String, Integer> LANGUAGE_IDS =
      Map.of("java", 62, "python", 71, "cpp", 54, "javascript", 63, "go", 60, "rust", 73);
  /** Judge0 在 cgroup v1 缺失或 isolate 无法建立 /box 时返回的内部错误状态码。 */
  private static final int JUDGE0_INTERNAL_ERROR_STATUS = 13;

  private final Judge0OjProperties properties;
  private final HttpClient client;
  private final JsonMapper json = JsonMapper.builder().build();
  /**
   * 已验证可用的 rlimit 模式；null 表示尚未确认，按配置值尝试。
   * 只在请求成功（且不是 Judge0 内部错误）后记录，避免两种模式来回抖动。
   */
  private volatile Boolean workingRlimitMode;

  public Judge0OjAdapter(Judge0OjProperties properties) {
    properties.validate();
    this.properties = properties;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .build();
  }

  @Override
  public OjJudgeResult judge(OjSubmission submission) {
    Integer languageId = LANGUAGE_IDS.get(submission.language());
    if (languageId == null || submission.testCases().isEmpty())
      return internal(submission.testCases());
    int passed = 0;
    int maxTimeMs = 0;
    int maxMemoryKb = 0;
    OjVerdict verdict = OjVerdict.ACCEPTED;
    String safeError = null;
    var results = new ArrayList<OjJudgeResult.TestCaseResult>();
    // 逐进程 rlimit 兼容开关：部署在 cgroup v2 主机时必须在首次请求就打开，
    // 否则 isolate 无法创建控制组，整批判题都会变成内部错误。
    boolean useRlimit =
        workingRlimitMode != null ? workingRlimitMode : properties.isUseRlimitFallback();
    try {
      for (int index = 0; index < submission.testCases().size(); index++) {
        var testCase = submission.testCases().get(index);
        var attempt = submit(submission, languageId, testCase, useRlimit);

        if (attempt.httpStatus() == 422 && useRlimit) {
          // judge0.conf 未允许 enable_per_process_and_thread_* 时会拒绝请求，
          // 退回默认隔离模式重试一次，并提示运维补齐配置。
          log.warn(
              "Judge0 rejected per-process rlimit fields (http 422), retrying without them;"
                  + " set ALLOW_ENABLE_PER_PROCESS_AND_THREAD_TIME_LIMIT and"
                  + " ALLOW_ENABLE_PER_PROCESS_AND_THREAD_MEMORY_LIMIT in judge0.conf if the host uses cgroup v2");
          useRlimit = false;
          attempt = submit(submission, languageId, testCase, false);
        } else if (statusId(attempt.body()) == JUDGE0_INTERNAL_ERROR_STATUS && !useRlimit) {
          // 典型原因是 cgroup v2 主机上 isolate 无法创建 /box；改用 rlimit 重试。
          log.warn(
              "Judge0 returned internal error (status 13) for language {}; retrying with per-process"
                  + " rlimit. On cgroup v2 hosts also set OJ_RLIMIT_FALLBACK=true",
              submission.language());
          useRlimit = true;
          attempt = submit(submission, languageId, testCase, true);
        }

        int resolvedStatus = statusId(attempt.body());
        if (attempt.httpStatus() / 100 == 2 && resolvedStatus >= 0 && resolvedStatus != JUDGE0_INTERNAL_ERROR_STATUS)
          workingRlimitMode = useRlimit;

        if (attempt.httpStatus() / 100 != 2) {
          log.warn(
              "Judge0 request failed: httpStatus={} detail={}",
              attempt.httpStatus(),
              safeDetail(attempt.body()));
          return internal(submission.testCases());
        }
        var root = attempt.body();
        if (statusId(root) < 0) {
          log.warn(
              "Judge0 response without status field: httpStatus={} detail={}",
              attempt.httpStatus(),
              safeDetail(root));
          return internal(submission.testCases());
        }
        var current = verdict(statusId(root));
        int timeMs = millis(root.path("time").asText("0"));
        int memoryKb = root.path("memory").asInt(0);
        if (current == OjVerdict.INTERNAL_ERROR) {
          log.warn(
              "Judge0 reported internal error for language {} test #{}: description={}",
              submission.language(),
              index + 1,
              statusDescription(root));
        }
        results.add(
            new OjJudgeResult.TestCaseResult(
                index + 1, current, timeMs, memoryKb, safeOrNull(current)));
        if (current != OjVerdict.ACCEPTED) {
          if (verdict == OjVerdict.ACCEPTED) {
            verdict = current;
            safeError = safe(current);
          }
        } else {
          passed++;
        }
        maxTimeMs = Math.max(maxTimeMs, timeMs);
        maxMemoryKb = Math.max(maxMemoryKb, memoryKb);
      }
    } catch (Exception error) {
      log.warn(
          "Judge0 request failed for language {}: {}",
          submission.language(),
          error.getClass().getSimpleName());
      return internal(submission.testCases());
    }
    return new OjJudgeResult(
        verdict,
        passed,
        submission.testCases().size(),
        maxTimeMs,
        maxMemoryKb,
        safeError,
        results);
  }

  private Attempt submit(
      OjSubmission submission, int languageId, OjSubmission.TestCase testCase, boolean useRlimit)
      throws Exception {
    var payload = new LinkedHashMap<String, Object>();
    // Always use base64 mode: with base64_encoded=false Judge0 fails to render
    // responses whose source/stdout/expected output contains non-ASCII text and
    // returns {"error": ...} without a status field.
    payload.put("source_code", encode(submission.code()));
    payload.put("language_id", languageId);
    payload.put("stdin", encode(testCase.input()));
    payload.put("expected_output", encode(testCase.expectedOutput()));
    payload.put("cpu_time_limit", properties.getCpuTimeLimitSeconds());
    payload.put("memory_limit", properties.getMemoryLimitKb());
    if (useRlimit) {
      payload.put("enable_per_process_and_thread_time_limit", true);
      payload.put("enable_per_process_and_thread_memory_limit", true);
    }
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    properties.getBaseUrl().replaceAll("/$", "")
                        + "/submissions?base64_encoded=true&wait=true"))
            .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json");
    if (properties.getApiToken() != null && !properties.getApiToken().isBlank())
      request.header("X-Auth-Token", properties.getApiToken());
    var response =
        client.send(
            request.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build(),
            HttpResponse.BodyHandlers.ofByteArray());
    byte[] bytes = response.body();
    if (bytes != null && bytes.length > properties.getMaxResponseBytes())
      throw new IllegalStateException("Judge0 response too large");
    JsonNode root = null;
    if (bytes != null && bytes.length > 0) {
      try {
        root = json.readTree(new String(bytes, StandardCharsets.UTF_8));
      } catch (RuntimeException error) {
        root = null;
      }
    }
    return new Attempt(response.statusCode(), root);
  }

  private static int statusId(JsonNode root) {
    if (root == null) return -1;
    var status = root.path("status");
    return status.path("id").isInt() ? status.path("id").asInt() : -1;
  }

  private static String statusDescription(JsonNode root) {
    if (root == null) return "unavailable";
    String raw = root.path("status").path("description").asText("");
    return raw.isBlank() ? "unavailable" : decodeIfBase64(raw);
  }

  /**
   * 只提取错误字段名或 error 文本，避免把源码、stdin、期望输出写进日志。
   */
  private static String safeDetail(JsonNode root) {
    if (root == null || root.isNull()) return "empty body";
    var error = root.path("error");
    if (error.isTextual()) return truncate(error.asText());
    if (root.isObject()) {
      var names = new ArrayList<String>();
      for (var field : root.properties()) names.add(field.getKey());
      if (!names.isEmpty()) return "fields=" + String.join(",", names);
    }
    return "unparsed response";
  }

  private static String decodeIfBase64(String value) {
    try {
      return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
    } catch (IllegalArgumentException error) {
      return value;
    }
  }

  private static String truncate(String value) {
    return value.length() <= 200 ? value : value.substring(0, 200);
  }

  private static String encode(String value) {
    return Base64.getEncoder()
        .encodeToString((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
  }

  private static OjVerdict verdict(int statusId) {
    return switch (statusId) {
      case 3 -> OjVerdict.ACCEPTED;
      case 4 -> OjVerdict.WRONG_ANSWER;
      case 5 -> OjVerdict.TIME_LIMIT_EXCEEDED;
      case 6 -> OjVerdict.COMPILATION_ERROR;
      case 7, 8, 9, 10, 11, 12, 14 -> OjVerdict.RUNTIME_ERROR;
      case 15 -> OjVerdict.MEMORY_LIMIT_EXCEEDED;
      default -> OjVerdict.INTERNAL_ERROR;
    };
  }

  private static int millis(String value) {
    try {
      return Math.max(0, (int) Math.round(Double.parseDouble(value) * 1000));
    } catch (NumberFormatException error) {
      return 0;
    }
  }

  private static String safe(OjVerdict verdict) {
    return switch (verdict) {
      case WRONG_ANSWER -> "synthetic wrong answer";
      case TIME_LIMIT_EXCEEDED -> "synthetic time limit exceeded";
      case MEMORY_LIMIT_EXCEEDED -> "synthetic memory limit exceeded";
      case RUNTIME_ERROR -> "synthetic runtime error";
      case COMPILATION_ERROR -> "synthetic compilation failure";
      default -> "synthetic judge0 status unavailable";
    };
  }

  private static String safeOrNull(OjVerdict verdict) {
    return verdict == OjVerdict.ACCEPTED ? null : safe(verdict);
  }

  private static OjJudgeResult internal(List<OjSubmission.TestCase> testCases) {
    var results = new ArrayList<OjJudgeResult.TestCaseResult>();
    for (int index = 0; index < testCases.size(); index++) {
      results.add(
          new OjJudgeResult.TestCaseResult(
              index + 1,
              OjVerdict.INTERNAL_ERROR,
              0,
              0,
              "synthetic judge0 response unavailable"));
    }
    return new OjJudgeResult(
        OjVerdict.INTERNAL_ERROR,
        0,
        testCases.size(),
        0,
        0,
        "synthetic judge0 response unavailable",
        results);
  }

  private record Attempt(int httpStatus, JsonNode body) {}
}
