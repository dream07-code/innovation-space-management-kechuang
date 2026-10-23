package io.github.dream07code.innovationspace.assessment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dream07code.innovationspace.assessment.service.AssessmentAiProvider;
import io.github.dream07code.innovationspace.assessment.service.AssessmentAiProviderRegistry;
import io.github.dream07code.innovationspace.assessment.service.HeuristicAssessmentAiProvider;
import io.github.dream07code.innovationspace.assessment.service.MockAssessmentScoringService;
import io.github.dream07code.innovationspace.assessment.service.OpenAiCompatibleAssessmentAiProvider;
import io.github.dream07code.innovationspace.scoring.ScoringProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class HeuristicAssessmentAiProviderTest {
  private final HeuristicAssessmentAiProvider provider = new HeuristicAssessmentAiProvider();

  @Test
  void scoresAcceptedCodeAboveFailedCode() {
    var accepted = provider.evaluate(input("cpp", "// 矩阵快速幂\nint main(){return 0;}", judge(OjVerdict.ACCEPTED, 7, 7)));
    var compilation =
        provider.evaluate(input("cpp", "int main(){", judge(OjVerdict.COMPILATION_ERROR, 0, 7)));

    assertTrue(accepted.score() >= 85, "accepted code scored " + accepted.score());
    assertTrue(compilation.score() <= 20, "failed code scored " + compilation.score());
    assertEquals("heuristic-assessment", accepted.model());
    assertTrue(accepted.reason().contains("测试点"));
  }

  @Test
  void penalizesDebugResidueAndPartialPasses() {
    var clean = provider.evaluate(input("python", "print(1)", judge(OjVerdict.ACCEPTED, 7, 7)));
    var partial =
        provider.evaluate(input("python", "print(1)  # TODO", judge(OjVerdict.WRONG_ANSWER, 3, 7)));

    assertTrue(partial.score() < clean.score());
    assertTrue(partial.reason().contains("3/7"));
  }

  @Test
  void keepsScoreWithinBoundsForInternalError() {
    var result = provider.evaluate(input("java", "class Main {}", judge(OjVerdict.INTERNAL_ERROR, 0, 6)));
    assertEquals(0, result.score());
    assertTrue(result.score() >= 0 && result.score() <= 100);
  }

  @Test
  void registryResolvesHeuristicProvider() {
    var properties = new ScoringProperties();
    properties.setEnabled(true);
    properties.setProvider("heuristic");
    var registry =
        new AssessmentAiProviderRegistry(
            properties,
            List.of(
                new HeuristicAssessmentAiProvider(),
                new MockAssessmentScoringService(),
                new OpenAiCompatibleAssessmentAiProvider(disabledProperties())));

    assertTrue(registry.enabled());
    assertTrue(registry.local());
    assertTrue(registry.provider() instanceof HeuristicAssessmentAiProvider);
  }

  @Test
  void registryRejectsUnknownProviderInsteadOfSilentlyFailing() {
    var properties = new ScoringProperties();
    properties.setEnabled(true);
    properties.setProvider("mock");
    var registry =
        new AssessmentAiProviderRegistry(
            properties, List.of(new MockAssessmentScoringService()));
    assertTrue(registry.local());
    assertThrows(
        IllegalArgumentException.class,
        () -> {
          properties.setProvider("unknown");
          registry.provider();
        });
  }

  private static ScoringProperties disabledProperties() {
    var properties = new ScoringProperties();
    properties.setEnabled(false);
    properties.setProvider("openai-compatible");
    properties.setBaseUrl("http://127.0.0.1:1");
    properties.setApiKey("test-key");
    properties.setModel("test-model");
    return properties;
  }

  private static AssessmentAiProvider.AssessmentAiInput input(
      String language, String code, OjJudgeResult judge) {
    return new AssessmentAiProvider.AssessmentAiInput("9201", language, code, judge);
  }

  private static OjJudgeResult judge(OjVerdict verdict, int passed, int total) {
    return new OjJudgeResult(verdict, passed, total, 12, 2048, null);
  }
}
