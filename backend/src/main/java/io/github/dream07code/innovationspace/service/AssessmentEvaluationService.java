package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.util.*;
import io.github.dream07code.innovationspace.assessment.*;
import io.github.dream07code.innovationspace.report.*;
import io.github.dream07code.innovationspace.dashboard.*;
import io.github.dream07code.innovationspace.member.*;
import io.github.dream07code.innovationspace.membership.*;
import io.github.dream07code.innovationspace.points.*;

public interface AssessmentEvaluationService {
  /** 判题完成后重算成绩：自动分按通过测试点计算，人工分优先。 */
  void evaluateAfterJudge(long submissionId);

  /** 重新汇总某个提交的成绩（人工评分后也调用）。 */
  void refreshResult(long submissionId);
}
