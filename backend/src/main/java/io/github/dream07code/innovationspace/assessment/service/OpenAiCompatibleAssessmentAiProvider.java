package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OpenAiCompatibleAssessmentAiProvider implements AssessmentAiProvider {
  private static final String RUBRIC =
      "Score assessment code from 0 to 100 for correctness, clarity, robustness, and alignment with the OJ result. Return JSON only with score and reason.";
  private final ScoringProperties properties;
  private final HttpClient client;
  private final JsonMapper json = JsonMapper.builder().build();

  public OpenAiCompatibleAssessmentAiProvider(ScoringProperties properties) {
    properties.validate();
    this.properties = properties;
    this.client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
            .build();
  }

  @Override
  public AssessmentAiScore evaluate(AssessmentAiInput input) {
    try {
      var payload =
          json.writeValueAsString(
              Map.of(
                  "model",
                  properties.getModel(),
                  "messages",
                  java.util.List.of(
                      Map.of("role", "system", "content", RUBRIC),
                      Map.of(
                          "role",
                          "user",
                          "content",
                          json.writeValueAsString(
                              Map.of(
                                  "language",
                                  input.language(),
                                  "code",
                                  input.code(),
                                  "ojVerdict",
                                  input.judgeResult().verdict().name(),
                                  "passedTestCases",
                                  input.judgeResult().passedTestCases(),
                                  "totalTestCases",
                                  input.judgeResult().totalTestCases()))))));
      var request =
          HttpRequest.newBuilder(
                  URI.create(properties.getBaseUrl().replaceAll("/$", "") + "/v1/chat/completions"))
              .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
              .header("Authorization", "Bearer " + properties.getApiKey())
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(payload))
              .build();
      var response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (var stream = response.body()) {
        byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
        if (response.statusCode() / 100 != 2)
          throw new IllegalStateException("AI provider HTTP " + response.statusCode());
        if (bytes.length > 1024 * 1024)
          throw new IllegalStateException("AI provider response exceeds 1MB");
        var root = json.readTree(new String(bytes, StandardCharsets.UTF_8));
        var content = root.at("/choices/0/message/content").asText();
        if (content == null || content.isBlank())
          throw new IllegalStateException("AI provider returned empty content");
        var score = json.readTree(stripCodeFence(content));
        int value = score.path("score").asInt(-1);
        String reason = score.path("reason").asText(null);
        if (value < 0 || value > 100)
          throw new IllegalStateException("AI provider returned score " + value);
        if (reason == null || reason.isBlank())
          throw new IllegalStateException("AI provider returned empty reason");
        return new AssessmentAiScore(value, reason, properties.getModel());
      }
    } catch (java.net.http.HttpTimeoutException error) {
      throw new IllegalStateException("AI provider timeout");
    } catch (java.net.ConnectException error) {
      throw new IllegalStateException("AI provider connection refused");
    } catch (java.net.UnknownHostException error) {
      throw new IllegalStateException("AI provider host not found");
    } catch (java.io.IOException error) {
      throw new IllegalStateException("AI provider network error: " + error.getClass().getSimpleName());
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("AI provider interrupted");
    }
  }

  /** 有些兼容端点会把 JSON 包在 ```json 代码块里，去掉围栏再解析。 */
  private static String stripCodeFence(String content) {
    String text = content.trim();
    if (!text.startsWith("```")) return text;
    int start = text.indexOf('\n');
    int end = text.lastIndexOf("```");
    if (start < 0 || end <= start) return text;
    return text.substring(start + 1, end).trim();
  }
}
