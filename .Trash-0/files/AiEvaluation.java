package io.github.dream07code.innovationspace.assessment;
public record AiEvaluation(
    String id,
    String submissionId,
    String status,
    Integer score,
    String reason,
    String model,
    String rubricVersion) {
  public AiEvaluation {
    if (score != null && (score < 0 || score > 100)) throw new IllegalArgumentException("invalid AI score");
    if (reason != null && reason.length() > 1000) throw new IllegalArgumentException("reason is too large");
  }
}
