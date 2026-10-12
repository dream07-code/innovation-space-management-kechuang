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
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public class Judge0OjAdapter implements OjAdapter {
  private static final Map<String, Integer> LANGUAGE_IDS =
      Map.of("java", 62, "python", 71, "cpp", 54, "javascript", 63, "go", 60, "rust", 73);
  private final Judge0OjProperties properties;
  private final HttpClient client;
  private final JsonMapper json = JsonMapper.builder().build();

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
      return internal(submission.testCases().size());
    int passed = 0;
    int maxTimeMs = 0;
    int maxMemoryKb = 0;
    OjVerdict verdict = OjVerdict.ACCEPTED;
    String safeError = null;
    try {
      for (var testCase : submission.testCases()) {
        var root = submit(submission, languageId, testCase);
        var status = root.path("status");
        int statusId = status.path("id").asInt(-1);
        var current = verdict(statusId);
        if (current != OjVerdict.ACCEPTED) {
          if (verdict == OjVerdict.ACCEPTED) {
            verdict = current;
            safeError = safe(current);
          }
        } else {
          passed++;
        }
        maxTimeMs = Math.max(maxTimeMs, millis(root.path("time").asText("0")));
        maxMemoryKb = Math.max(maxMemoryKb, root.path("memory").asInt(0));
      }
    } catch (Exception error) {
      return internal(submission.testCases().size());
    }
    return new OjJudgeResult(
        verdict, passed, submission.testCases().size(), maxTimeMs, maxMemoryKb, safeError);
  }

  private JsonNode submit(
      OjSubmission submission, int languageId, OjSubmission.TestCase testCase) throws Exception {
    var payload = new LinkedHashMap<String, Object>();
    payload.put("source_code", submission.code());
    payload.put("language_id", languageId);
    payload.put("stdin", testCase.input());
    payload.put("expected_output", testCase.expectedOutput());
    payload.put("cpu_time_limit", properties.getCpuTimeLimitSeconds());
    payload.put("memory_limit", properties.getMemoryLimitKb());
    var request =
        HttpRequest.newBuilder(
                URI.create(
                    properties.getBaseUrl().replaceAll("/$", "")
                        + "/submissions?base64_encoded=false&wait=true"))
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
    if (response.statusCode() / 100 != 2 || bytes.length > properties.getMaxResponseBytes())
      throw new IllegalStateException("Judge0 response unavailable");
    return json.readTree(new String(bytes, StandardCharsets.UTF_8));
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

  private static OjJudgeResult internal(int total) {
    return new OjJudgeResult(
        OjVerdict.INTERNAL_ERROR, 0, total, 0, 0, "synthetic judge0 response unavailable");
  }
}
