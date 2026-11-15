package io.github.dream07code.innovationspace.problemgen;

import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.json.JsonMapper;

/**
 * 通过 openai-compatible 接口出题，复用批改/助教的 {@code app.ai.*} 配置。
 *
 * <p>与其它 AI 调用一致：{@code mock}/{@code heuristic} 只服务报告评分，用到这里会产出
 * 固定模板题，因此按「未配置」处理，直接不可用。
 *
 * <p>解析失败抛 {@link ProblemDraftParser.InvalidDraft}，由服务层带着失败原因重试一次；
 * 网络/鉴权失败抛 {@link Unavailable}，不做无意义的重试。
 */
public class OpenAiCompatibleProblemGenerator implements ProblemGenerator {
  private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

  private final ScoringProperties properties;
  private final HttpClient client;
  private final JsonMapper json = JsonMapper.builder().build();

  public OpenAiCompatibleProblemGenerator(ScoringProperties properties) {
    this.properties = properties;
    properties.validate();
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .build();
  }

  @Override
  public ProblemDraft generate(Request request) {
    if (!properties.isEnabled() || !"openai-compatible".equals(properties.getProvider()))
      throw new Unavailable("AI problem generator is not configured");
    try {
      var body = json.writeValueAsString(requestBody(request));
      var httpRequest =
          HttpRequest.newBuilder(URI.create(endpoint()))
              .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
              .header("Authorization", "Bearer " + properties.getApiKey())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(body))
              .build();
      var response = client.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
        if (bytes.length > MAX_RESPONSE_BYTES) throw new Unavailable("AI response too large");
        if (response.statusCode() / 100 != 2) throw new Unavailable("AI generation request failed");
        var content =
            json.readTree(new String(bytes, StandardCharsets.UTF_8))
                .at("/choices/0/message/content")
                .asText();
        return ProblemDraftParser.parse(content);
      }
    } catch (Unavailable | ProblemDraftParser.InvalidDraft error) {
      throw error;
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new Unavailable("AI generation interrupted", error);
    } catch (Exception error) {
      throw new Unavailable("AI generation unavailable", error);
    }
  }

  private Map<String, Object> requestBody(Request request) {
    var body = new LinkedHashMap<String, Object>();
    body.put("model", properties.getModel());
    body.put("messages", ProblemGenerationRubric.messages(request));
    return body;
  }

  private String endpoint() {
    return properties.getBaseUrl().replaceAll("/+$", "") + "/v1/chat/completions";
  }
}
