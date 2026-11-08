package io.github.dream07code.innovationspace.scoring;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通过 openai-compatible 接口调用与日报/周报评分相同的模型，产出助教回答。
 *
 * <p>只认 {@code AI_PROVIDER=openai-compatible}：{@code mock}/{@code heuristic} 是给报告评分用的
 * 固定规则实现，用到这里会把模板句当成教学回答，因此直接按不可用处理。
 *
 * <p>输出是自然语言，不做 JSON 解析；模型偶尔会自己加 Markdown 围栏，这里做一次轻量清理，
 * 但不会为了「看起来整齐」而改写内容本身。
 */
public class OpenAiCompatibleSubmissionTutorAi implements SubmissionTutorAi {
  private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
  private static final int MAX_REPLY_CHARS = 4000;

  private final ScoringProperties properties;
  private final HttpClient client;
  private final JsonMapper json = JsonMapper.builder().build();

  public OpenAiCompatibleSubmissionTutorAi(ScoringProperties properties) {
    this.properties = properties;
    properties.validate();
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .build();
  }

  @Override
  public Reply chat(TutorInput input) {
    if (!properties.isEnabled() || !"openai-compatible".equals(properties.getProvider()))
      throw new Unavailable("AI tutor provider is not configured");
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
        if (response.statusCode() / 100 != 2) throw new Unavailable("AI tutor request failed");
        var content =
            json.readTree(new String(bytes, StandardCharsets.UTF_8))
                .at("/choices/0/message/content")
                .asText();
        return new Reply(normalize(content), properties.getModel());
      }
    } catch (Unavailable error) {
      throw error;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new Unavailable("AI tutor interrupted", error);
    } catch (Exception error) {
      throw new Unavailable("AI tutor unavailable", error);
    }
  }

  private Map<String, Object> requestBody(TutorInput input) {
    var body = new LinkedHashMap<String, Object>();
    body.put("model", properties.getModel());
    body.put("messages", SubmissionTutorRubric.messages(input));
    return body;
  }

  private String endpoint() {
    return properties.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
  }

  /** 去掉模型偶发加的整段 Markdown 围栏；空回答按不可用处理，不让前端显示空气泡。 */
  private static String normalize(String content) {
    if (content == null) throw new Unavailable("AI returned empty content");
    var text = content.strip();
    if (text.startsWith("```")) {
      int firstLineEnd = text.indexOf('\n');
      if (firstLineEnd > 0) text = text.substring(firstLineEnd + 1).strip();
      if (text.endsWith("```")) text = text.substring(0, text.length() - 3).strip();
    }
    if (text.isBlank()) throw new Unavailable("AI returned empty content");
    return text.length() <= MAX_REPLY_CHARS ? text : text.substring(0, MAX_REPLY_CHARS);
  }

  /** 供测试断言请求体结构，避免只在生产路径上才拼错 messages。 */
  List<Map<String, Object>> messagesFor(TutorInput input) {
    return SubmissionTutorRubric.messages(input);
  }
}
