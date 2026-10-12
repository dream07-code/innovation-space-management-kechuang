package io.github.dream07code.innovationspace.assessment;

import io.github.dream07code.innovationspace.assessment.service.MockAssessmentReviewService;
import io.github.dream07code.innovationspace.assessment.service.MockAssessmentScoringService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssessmentScoringTest {
  @Test
  void aiAndHumanScoresAreIndependentAndHumanReviewCanReplaceAi() {
    var scoring = new MockAssessmentScoringService();
    var ai = scoring.evaluate("sub-1", new OjJudgeResult(OjVerdict.ACCEPTED, 5, 5, 12, 2048, null), "public class Main {}");
    assertEquals(85, ai.score());
    assertEquals("COMPLETE", ai.status());

    var review = new MockAssessmentReviewService().review("sub-1", 92, "结构清晰", 1);
    assertEquals(92, review.score());
    assertEquals(ReviewStatus.ACTIVE, review.status());
    assertTrue(String.valueOf(review).contains("结构清晰"));
    assertFalse(String.valueOf(review).contains("public class Main"));
  }
}
