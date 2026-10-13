package io.github.dream07code.innovationspace.assessment.adapter;

import io.github.dream07code.innovationspace.assessment.OjJudgeResult;
import io.github.dream07code.innovationspace.assessment.OjSubmission;
import io.github.dream07code.innovationspace.assessment.OjVerdict;
import java.util.ArrayList;

public class MockOjAdapter implements OjAdapter {
  @Override
  public OjJudgeResult judge(OjSubmission submission) {
    String code = submission.code();
    OjVerdict failure = failureVerdict(code);
    int total = submission.testCases().size();
    int passed =
        switch (failure) {
          case COMPILATION_ERROR -> 0;
          case ACCEPTED -> total;
          default -> Math.max(0, total - 1);
        };
    var results = new ArrayList<OjJudgeResult.TestCaseResult>();
    for (int index = 0; index < total; index++) {
      OjVerdict verdict = index < passed ? OjVerdict.ACCEPTED : failure;
      String safeError = verdict == OjVerdict.ACCEPTED ? null : safe(verdict);
      int timeMs = verdict == OjVerdict.TIME_LIMIT_EXCEEDED ? 30000 : verdict == OjVerdict.ACCEPTED ? 12 : 10;
      int memoryKb = verdict == OjVerdict.ACCEPTED ? 2048 : verdict == OjVerdict.TIME_LIMIT_EXCEEDED ? 4096 : 1024;
      results.add(new OjJudgeResult.TestCaseResult(index + 1, verdict, timeMs, memoryKb, safeError));
    }
    int maxTimeMs = results.stream().mapToInt(OjJudgeResult.TestCaseResult::timeMs).max().orElse(0);
    int maxMemoryKb = results.stream().mapToInt(OjJudgeResult.TestCaseResult::memoryKb).max().orElse(0);
    return new OjJudgeResult(
        failure,
        passed,
        total,
        maxTimeMs,
        maxMemoryKb,
        failure == OjVerdict.ACCEPTED ? null : safe(failure),
        results);
  }

  private static OjVerdict failureVerdict(String code) {
    if (code.contains("compile-error")) return OjVerdict.COMPILATION_ERROR;
    if (code.contains("wrong-answer")) return OjVerdict.WRONG_ANSWER;
    if (code.contains("timeout")) return OjVerdict.TIME_LIMIT_EXCEEDED;
    if (code.contains("runtime-error")) return OjVerdict.RUNTIME_ERROR;
    return OjVerdict.ACCEPTED;
  }

  private static String safe(OjVerdict verdict) {
    return switch (verdict) {
      case WRONG_ANSWER -> "synthetic wrong answer";
      case TIME_LIMIT_EXCEEDED -> "synthetic time limit exceeded";
      case MEMORY_LIMIT_EXCEEDED -> "synthetic memory limit exceeded";
      case RUNTIME_ERROR -> "synthetic runtime failure";
      case COMPILATION_ERROR -> "synthetic compilation failure";
      default -> "synthetic judge unavailable";
    };
  }
}
