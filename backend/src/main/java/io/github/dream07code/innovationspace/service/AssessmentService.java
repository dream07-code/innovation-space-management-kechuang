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

public interface AssessmentService {
  IdempotencyService.StoredResponse create( Map<String, Object> request, SessionPrincipal actor, String key);
  Map<String, Object> get(long id, SessionPrincipal actor);
  Map<String, Object> list( SessionPrincipal actor, int page, int size, String status, String directionValue);
  Map<String, Object> update(long id, Map<String, Object> request, SessionPrincipal actor);
  IdempotencyService.StoredResponse command( long id, Map<String, Object> request, SessionPrincipal actor, String key, String action);
  /** 到期自动关闭：PUBLISHED 且 end_at 已过的考核置为 CLOSED，不结算积分，返回处理数。 */
  int closeExpiredAssessments();
  /** 发布成绩：阅卷完成后由负责人/管理员手动触发，同时结算考核积分。 */
  IdempotencyService.StoredResponse publishResults( long id, Map<String, Object> request, SessionPrincipal actor, String key);
}
