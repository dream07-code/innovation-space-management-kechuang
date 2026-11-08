package io.github.dream07code.innovationspace.scoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通过 openai-compatible 接口调用与日报/周报评分相同的模型，产出编程作业批改。
 *
 * <p>只认 {@code AI_PROVIDER=openai-compatible}：{@code mock}/{@code heuristic} 只服务报告评分，
 * 不用于批改，避免把固定结论当成真实批改展示。
 */
public class OpenAiCompatibleSubmissionReportAi implements SubmissionReportAi {
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final int MAX_CAUSE_CHARS = 1000;
  private static final int MAX_ADVICE_ITEMS = 5;
  private static final int MAX_ADVICE_CHARS = 300;

  private final ScoringProperties properties;
  private final HttpClient client;
  private final JsonMapper json = JsonMapper.builder().build();

  public OpenAiCompatibleSubmissionReportAi(ScoringProperties properties) {
    this.properties = properties;
    properties.validate();
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .build();
  }

  @Override
  public Analysis analyze(ReportInput input) {
    if (!properties.isEnabled() || !"openai-compatible".equals(properties.getProvider()))
      throw new Unavailable("AI report provider is not configured");
    try {
      var body = json.writeValueAsString(requestBody(input));
      var request =
          HttpRequest.newBuilder(URI.create(endpoint()))
              .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
              .header("Authorization", "Bearer " + properties.getApiKey())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) throw new Unavailable("AI response too large");
        if (response.statusCode() / 100 != 2) throw new Unavailable("AI report request failed");
        var content =
            json.readTree(new String(bytes, StandardCharsets.UTF_8))
                .at("/choices/0/message/content")
                .asText();
        return parse(content);
      }
    } catch (Unavailable error) {
      throw error;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new Unavailable("AI report interrupted", error);
    } catch (Exception error) {
      throw new Unavailable("AI report unavailable", error);
    }
  }

  private Map<String, Object> requestBody(ReportInput input) {
    var system = new LinkedHashMap<String, Object>();
    system.put("role", "system");
    system.put("content", SubmissionReportRubric.system());
    var user = new LinkedHashMap<String, Object>();
    user.put("role", "user");
    user.put("content", SubmissionReportRubric.user(input));
    var body = new LinkedHashMap<String, Object>();
    body.put("model", properties.getModel());
    body.put("messages", List.of(system, user));
    return body;
  }

  private String endpoint() {
    return properties.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
  }

  /**
   * 解析模型输出。模型偶尔会用 ```json 包裹或加一句解释，这里只取第一个 JSON 对象，
   * 缺字段或结构不对一律按不可用处理，不做猜测性补全。
   */
  private Analysis parse(String content) {
    if (content == null || content.isBlank()) throw new Unavailable("AI returned empty content");
    int start = content.indexOf('{');
    int end = content.lastIndexOf('}');
    if (start < 0 || end <= start) throw new Unavailable("AI returned non-JSON content");
    JsonNode root;
    try {
      root = json.readTree(content.substring(start, end + 1));
    } catch (RuntimeException error) {
      throw new Unavailable("AI returned malformed JSON", error);
    }
    String cause = text(root.path("cause"));
    if (cause.isBlank()) throw new Unavailable("AI returned empty cause");
    var advice = new ArrayList<String>();
    for (JsonNode node : root.path("advice")) {
      String item = text(node);
      if (!item.isBlank()) advice.add(cap(item, MAX_ADVICE_CHARS));
      if (advice.size() >= MAX_ADVICE_ITEMS) break;
    }
    return new Analysis(cap(cause, MAX_CAUSE_CHARS), advice, properties.getModel());
  }

  private static String text(JsonNode node) {
    return node == null || node.isNull() ? "" : node.asText("").trim();
  }

  private static String cap(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
}
