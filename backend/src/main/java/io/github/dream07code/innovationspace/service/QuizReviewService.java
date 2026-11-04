package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/** 填空题人工阅卷：按题横向列表、批量评分、撤销与进度统计。 */
public interface QuizReviewService {

  /** 某道填空在全部已交卷学生中的作答与评分状态。 */
  Map<String, Object> review(
      long assessmentId,
      long questionId,
      String filter,
      int page,
      int size,
      SessionPrincipal actor);

  /** 阅卷进度与发布条件。 */
  Map<String, Object> summary(long assessmentId, SessionPrincipal actor);

  /** 已交卷答题卡中尚无人工分的填空明细数；发布成绩的前置条件。 */
  int pendingCount(long assessmentId);

  /** 批量评分：整体一个事务，任一条版本冲突即回滚。 */
  IdempotencyService.StoredResponse grade(
      long assessmentId, Map<String, Object> request, SessionPrincipal actor, String key);

  /** 撤销某条人工评分，回退到自动分。 */
  IdempotencyService.StoredResponse revoke(
      long assessmentId,
      long sheetId,
      int position,
      Map<String, Object> request,
      SessionPrincipal actor,
      String key);
}
