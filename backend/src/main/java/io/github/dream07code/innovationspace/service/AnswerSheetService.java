package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/** 学生试卷与客观题答题卡：草稿暂存、交卷统一判分、结果明细。 */
public interface AnswerSheetService {
  /** 试卷：PUBLISHED 且本方向可读；客观题条目绝不含 answer/analysis。 */
  Map<String, Object> paper(long assessmentId, SessionPrincipal actor);

  /** 草稿整体覆盖暂存：仅窗口开放内、DRAFT 状态可写。 */
  Map<String, Object> saveDraft(long assessmentId, Map<String, Object> request, SessionPrincipal actor);

  /** 交卷并同步判分（幂等）；窗口结束或已交卷时拒绝。 */
  IdempotencyService.StoredResponse submit(long assessmentId, SessionPrincipal actor, String key);

  /** 本人答题卡：考核 CLOSED 前剥离一切判分字段。 */
  Map<String, Object> mySheet(long assessmentId, SessionPrincipal actor);

  /** 逐生答题卡（负责人/管理员）；学生仅可查本人。 */
  Map<String, Object> sheets(
      long assessmentId, SessionPrincipal actor, int page, int size, Long memberId);
}
