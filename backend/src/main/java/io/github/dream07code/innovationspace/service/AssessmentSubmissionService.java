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

public interface AssessmentSubmissionService {
  IdempotencyService.StoredResponse create( long assessmentId, Map<String, Object> request, SessionPrincipal actor, String key);
  Map<String, Object> get(long id, SessionPrincipal actor);
  Map<String, Object> code(long id, SessionPrincipal actor);
  Map<String, Object> list( SessionPrincipal actor, int page, int size, String assessmentValue, String problemValue, String memberValue, String status);
}
