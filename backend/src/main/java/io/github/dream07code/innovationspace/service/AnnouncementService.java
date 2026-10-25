package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

public interface AnnouncementService {
  IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal actor, String key);

  /** 当前用户可见且未撤回的公告（学院级 + 本人相关方向），created_at 倒序。 */
  Map<String, Object> list(SessionPrincipal actor);

  IdempotencyService.StoredResponse withdraw(long id, SessionPrincipal actor, String key);
}
