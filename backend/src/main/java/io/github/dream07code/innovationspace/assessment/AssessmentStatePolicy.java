package io.github.dream07code.innovationspace.assessment;

import java.util.Set;

public final class AssessmentStatePolicy {
  public boolean canTransition(AssessmentStatus from, AssessmentStatus to) {
    return switch (from) {
      case DRAFT -> to == AssessmentStatus.PUBLISHED;
      case PUBLISHED -> to == AssessmentStatus.CLOSED;
      case CLOSED -> to == AssessmentStatus.ARCHIVED;
      case ARCHIVED -> false;
    };
  }

  public boolean canTransition(ProblemStatus from, ProblemStatus to) {
    return switch (from) {
      case DRAFT -> to == ProblemStatus.PUBLISHED;
      case PUBLISHED -> to == ProblemStatus.RETIRED;
      case RETIRED -> false;
    };
  }

  public boolean canTransition(SubmissionStatus from, SubmissionStatus to) {
    return switch (from) {
      case RECEIVED -> to == SubmissionStatus.JUDGING;
      case JUDGING -> Set.of(SubmissionStatus.JUDGED, SubmissionStatus.FAILED, SubmissionStatus.NEEDS_REVIEW).contains(to);
      case JUDGED, FAILED, NEEDS_REVIEW -> false;
    };
  }

  public boolean canTransition(ReviewStatus from, ReviewStatus to) {
    return from == ReviewStatus.ACTIVE && to == ReviewStatus.SUPERSEDED;
  }
}
