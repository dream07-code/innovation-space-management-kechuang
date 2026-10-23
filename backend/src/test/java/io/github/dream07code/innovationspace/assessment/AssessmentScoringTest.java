package io.github.dream07code.innovationspace.assessment;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dream07code.innovationspace.assessment.service.MockAssessmentReviewService;
import io.github.dream07code.innovationspace.service.impl.AssessmentEvaluationServiceImpl;
import org.junit.jupiter.api.Test;

class AssessmentScoringTest {
  @Test
  void automaticScoreIsThePassedTestCaseRatio() {
    assertEquals(100, AssessmentEvaluationServiceImpl.autoScore(judge(OjVerdict.ACCEPTED, 7, 7)));
    assertEquals(86, AssessmentEvaluationServiceImpl.autoScore(judge(OjVerdict.WRONG_ANSWER, 6, 7)));
    assertEquals(50, AssessmentEvaluationServiceImpl.autoScore(judge(OjVerdict.WRONG_ANSWER, 3, 6)));
    assertEquals(0, AssessmentEvaluationServiceImpl.autoScore(judge(OjVerdict.COMPILATION_ERROR, 0, 7)));
  }

  @Test
  void internalErrorsAndEmptyCasesDoNotGetAnAutomaticScore() {
    assertNull(AssessmentEvaluationServiceImpl.autoScore(judge(OjVerdict.INTERNAL_ERROR, 0, 7)));
    assertNull(AssessmentEvaluationServiceImpl.autoScore(judge(OjVerdict.ACCEPTED, 0, 0)));
  }

  @Test
  void humanReviewCanReplaceTheAutomaticScore() {
    var review = new MockAssessmentReviewService().review("sub-1", 92, "结构清晰", 1);
    assertEquals(92, review.score());
    assertEquals(ReviewStatus.ACTIVE, review.status());
    assertTrue(String.valueOf(review).contains("结构清晰"));
    assertFalse(String.valueOf(review).contains("public class Main"));
  }

  private static OjJudgeResult judge(OjVerdict verdict, int passed, int total) {
    return new OjJudgeResult(verdict, passed, total, 12, 2048, null);
  }
}
