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

public interface AssessmentProblemService {
  IdempotencyService.StoredResponse create( Map<String, Object> request, SessionPrincipal actor, String key);
  IdempotencyService.StoredResponse importProblems(Map<String, Object> request, SessionPrincipal actor, String key);
  Map<String, Object> get(long id, SessionPrincipal actor);
  Map<String, Object> list(SessionPrincipal actor, int page, int size);
  Map<String, Object> update(long id, Map<String, Object> request, SessionPrincipal actor);
  Map<String, Object> testCases(long id, SessionPrincipal actor);
  IdempotencyService.StoredResponse retire( long id, Map<String, Object> request, SessionPrincipal actor, String key);
}
