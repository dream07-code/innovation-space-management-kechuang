package io.github.dream07code.innovationspace.assessment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 阅卷开放、发布条件与学生可见性。 */
class QuizReviewPolicyTest {

  @Test
  void gradingOpenOnlyAfterCloseAndBeforePublish() {
    assertFalse(QuizReviewPolicy.canGrade("DRAFT", false));
    assertFalse(QuizReviewPolicy.canGrade("PUBLISHED", false));
    assertTrue(QuizReviewPolicy.canGrade("CLOSED", false));
    assertTrue(QuizReviewPolicy.canGrade("ARCHIVED", false));
    assertFalse(QuizReviewPolicy.canGrade("CLOSED", true));
    assertFalse(QuizReviewPolicy.canGrade("ARCHIVED", true));
  }

  @Test
  void publishRequiresClosedUnpublishedAndNoPendingItems() {
    assertFalse(QuizReviewPolicy.canPublish("DRAFT", false, 0));
    assertFalse(QuizReviewPolicy.canPublish("PUBLISHED", false, 0));
    assertFalse(QuizReviewPolicy.canPublish("CLOSED", true, 0));
    assertFalse(QuizReviewPolicy.canPublish("CLOSED", false, 1));
    assertTrue(QuizReviewPolicy.canPublish("CLOSED", false, 0));
    assertFalse(QuizReviewPolicy.canPublish("ARCHIVED", false, 0));
  }

  @Test
  void studentsSeeResultsOnlyAfterPublish() {
    assertFalse(QuizReviewPolicy.visibleToStudent(false));
    assertTrue(QuizReviewPolicy.visibleToStudent(true));
  }
}
