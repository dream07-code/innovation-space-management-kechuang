package io.github.dream07code.innovationspace.assessment;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AssessmentStatePolicyTest {
  @Test
  void assessmentProblemSubmissionAndReviewStatesTransitionOnlyForward() {
    var policy = new AssessmentStatePolicy();
    assertTrue(policy.canTransition(AssessmentStatus.DRAFT, AssessmentStatus.PUBLISHED));
    assertTrue(policy.canTransition(AssessmentStatus.PUBLISHED, AssessmentStatus.CLOSED));
    assertTrue(policy.canTransition(AssessmentStatus.CLOSED, AssessmentStatus.ARCHIVED));
    assertFalse(policy.canTransition(AssessmentStatus.PUBLISHED, AssessmentStatus.DRAFT));
    assertTrue(policy.canTransition(ProblemStatus.DRAFT, ProblemStatus.PUBLISHED));
    assertTrue(policy.canTransition(ProblemStatus.PUBLISHED, ProblemStatus.RETIRED));
    assertFalse(policy.canTransition(ProblemStatus.RETIRED, ProblemStatus.PUBLISHED));
    assertTrue(policy.canTransition(SubmissionStatus.RECEIVED, SubmissionStatus.JUDGING));
    assertTrue(policy.canTransition(SubmissionStatus.JUDGING, SubmissionStatus.JUDGED));
    assertFalse(policy.canTransition(SubmissionStatus.JUDGED, SubmissionStatus.RECEIVED));
    assertTrue(policy.canTransition(ReviewStatus.ACTIVE, ReviewStatus.SUPERSEDED));
    assertFalse(policy.canTransition(ReviewStatus.SUPERSEDED, ReviewStatus.ACTIVE));
  }
}
