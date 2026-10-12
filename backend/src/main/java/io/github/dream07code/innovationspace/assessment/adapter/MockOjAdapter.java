package io.github.dream07code.innovationspace.assessment.adapter;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;
import io.github.dream07code.innovationspace.assessment.OjVerdict;

public class MockOjAdapter implements OjAdapter {
  @Override
  public OjJudgeResult judge(OjSubmission submission) {
    String code = submission.code();
    if (code.contains("compile-error"))
      return new OjJudgeResult(OjVerdict.COMPILATION_ERROR, 0, 5, 0, 0, "synthetic compilation failure");
    if (code.contains("wrong-answer"))
      return new OjJudgeResult(OjVerdict.WRONG_ANSWER, 2, 5, 12, 2048, "synthetic wrong answer");
    if (code.contains("timeout"))
      return new OjJudgeResult(OjVerdict.TIME_LIMIT_EXCEEDED, 1, 5, 30000, 4096, "synthetic timeout");
    if (code.contains("runtime-error"))
      return new OjJudgeResult(OjVerdict.RUNTIME_ERROR, 1, 5, 10, 1024, "synthetic runtime failure");
    return new OjJudgeResult(OjVerdict.ACCEPTED, 5, 5, 12, 2048, null);
  }
}
