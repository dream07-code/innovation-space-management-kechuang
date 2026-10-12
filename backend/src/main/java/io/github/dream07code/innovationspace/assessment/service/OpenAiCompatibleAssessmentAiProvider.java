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
        if (bytes.length > 1024 * 1024 || response.statusCode() / 100 != 2)
          throw new IllegalStateException("AI provider request failed");
        var root = json.readTree(new String(bytes, StandardCharsets.UTF_8));
        var content = root.at("/choices/0/message/content").asText();
        var score = json.readTree(content);
        int value = score.path("score").asInt(-1);
        String reason = score.path("reason").asText(null);
        if (value < 0 || value > 100 || reason == null || reason.isBlank())
          throw new IllegalStateException("AI provider returned an invalid score");
        return new AssessmentAiScore(value, reason, properties.getModel());
      }
    } catch (Exception error) {
      throw new IllegalStateException("AI provider unavailable");
    }
  }
}
