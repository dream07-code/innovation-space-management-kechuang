package io.github.dream07code.innovationspace.assessment.adapter;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;

public interface OjAdapter {
  OjJudgeResult judge(OjSubmission submission);
}
