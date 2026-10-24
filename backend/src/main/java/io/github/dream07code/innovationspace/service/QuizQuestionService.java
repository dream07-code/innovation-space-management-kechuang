package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/** 客观题库管理：仅学院管理员与方向负责人可用，学生一律 404。 */
public interface QuizQuestionService {
  IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal actor, String key);

  Map<String, Object> get(long id, SessionPrincipal actor);

  Map<String, Object> list(
      SessionPrincipal actor, int page, int size, String type, String status, String keyword);

  Map<String, Object> update(long id, Map<String, Object> request, SessionPrincipal actor);

  IdempotencyService.StoredResponse retire(
      long id, Map<String, Object> request, SessionPrincipal actor, String key);
}
