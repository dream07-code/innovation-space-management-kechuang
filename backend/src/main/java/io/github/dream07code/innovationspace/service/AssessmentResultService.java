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

public interface AssessmentResultService {
  /** 按提交平铺的成绩（一次提交一行）；memberId 非空时只看这个成员。 */
  Map<String, Object> list(
      long assessmentId, SessionPrincipal actor, int page, int size, Long memberId);

  /**
   * 按成员汇总的成绩：每人一行，同一题目多次提交以最后一次为准。
   *
   * <p>总分是该成员各题最终分之和，平均分按考核题目总数计算（未提交的题按 0 分），
   * 便于直接横向比较同一场考核里的成员。
   */
  Map<String, Object> memberScores(long assessmentId, SessionPrincipal actor, int page, int size);
}
