package io.github.dream07code.innovationspace.assessment.service;

import io.github.dream07code.innovationspace.assessment.AssessmentReview;
import io.github.dream07code.innovationspace.assessment.ReviewStatus;

public class MockAssessmentReviewService {
  public AssessmentReview review(String submissionId, int score, String comment, long version) {
    return new AssessmentReview(
        "review-" + submissionId + "-" + version,
        submissionId,
        10L,
        20L,
        score,
        comment,
        ReviewStatus.ACTIVE,
        version);
  }
}
