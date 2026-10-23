package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;

public interface AssessmentAiProvider {
  AssessmentAiScore evaluate(AssessmentAiInput input);

  record AssessmentAiInput(
      String submissionId, String language, String code, OjJudgeResult judgeResult) {
    public AssessmentAiInput {
      if (submissionId == null || submissionId.isBlank() || language == null || language.isBlank()
          || code == null || code.isBlank() || judgeResult == null)
        throw new IllegalArgumentException("assessment AI input is incomplete");
      if (code.length() > 200000) throw new IllegalArgumentException("code is too large");
    }
  }

  record AssessmentAiScore(int score, String reason, String model) {
    public AssessmentAiScore {
      if (score < 0 || score > 100) throw new IllegalArgumentException("invalid AI score");
      if (reason == null || reason.isBlank() || reason.length() > 1000)
        throw new IllegalArgumentException("invalid AI reason");
      if (model == null || model.isBlank() || model.length() > 128)
        throw new IllegalArgumentException("invalid AI model");
    }
  }
}
