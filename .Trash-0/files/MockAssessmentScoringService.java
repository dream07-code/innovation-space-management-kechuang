package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.assessment.AiEvaluation;
import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import org.springframework.stereotype.Service;

@Service
public class MockAssessmentScoringService implements AssessmentAiProvider {
  @Override
  public AssessmentAiScore evaluate(AssessmentAiInput input) {
    int score = switch (input.judgeResult().verdict()) {
      case ACCEPTED -> 85;
      case WRONG_ANSWER -> 55;
      case TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED -> 35;
      case RUNTIME_ERROR, COMPILATION_ERROR, INTERNAL_ERROR -> 10;
    };
    return new AssessmentAiScore(score, "synthetic quality score", "mock-assessment-model");
  }

  public AiEvaluation evaluate(String submissionId, OjJudgeResult result, String code) {
    var score =
        evaluate(new AssessmentAiInput(submissionId, "java", code, result));
    return new AiEvaluation(
        "ai-" + submissionId,
        submissionId,
        "COMPLETE",
        score.score(),
        score.reason(),
        score.model(),
        "assessment-rubric-v1");
  }
}
