package io.github.dream07code.innovationspace.assessment.adapter;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;

public class OpenSourceOjAdapter implements OjAdapter {
  @Override
  public OjJudgeResult judge(OjSubmission submission) {
    throw new UnsupportedOperationException("Open-source OJ adapter selected during implementation");
  }
}
