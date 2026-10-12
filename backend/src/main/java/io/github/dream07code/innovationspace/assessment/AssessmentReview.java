package io.github.dream07code.innovationspace.assessment;
public record AssessmentReview(
    String id,
    String submissionId,
    long memberId,
    long reviewerId,
    int score,
    String comment,
    ReviewStatus status,
    long version) {
  public AssessmentReview {
    if (score < 0 || score > 100) throw new IllegalArgumentException("invalid review score");
    if (comment == null || comment.isBlank() || comment.length() > 2000)
      throw new IllegalArgumentException("invalid review comment");
  }
}
