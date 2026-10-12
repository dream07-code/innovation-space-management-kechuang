package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.assessment.AiEvaluation;
import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjVerdict;

public class MockAssessmentScoringService {
  public AiEvaluation evaluate(String submissionId, OjJudgeResult result, String code) {
    int score = switch (result.verdict()) {
      case ACCEPTED -> 85;
      case WRONG_ANSWER -> 55;
      case TIME_LIMIT_EXCEEDED, MEMORY_LIMIT_EXCEEDED -> 35;
      case RUNTIME_ERROR, COMPILATION_ERROR, INTERNAL_ERROR -> 10;
    };
    return new AiEvaluation(
        "ai-" + submissionId,
        submissionId,
        "COMPLETE",
        score,
        "synthetic quality score",
        "mock-assessment-model",
        "assessment-rubric-v1");
  }
}
