package io.github.dream07code.innovationspace.service;
import io.github.dream07code.innovationspace.service.impl.MemberServiceImpl;

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

public interface MemberService {
  Map<String, Object> map(Map<String, Object> r);
  Map<String, Object> row(long id);
  Map<String, Object> get(long id, SessionPrincipal actor);
  Map<String, Object> list( SessionPrincipal actor, int page, int size, String direction, String keyword, String status);
  IdempotencyService.StoredResponse create( Map<String, Object> request, SessionPrincipal actor, String key);
  Map<String, Object> update(long id, Map<String, Object> request, SessionPrincipal actor);
  void lockAndVersion(long id, Map<String, Object> request, SessionPrincipal actor);
  Map<String, Object> lockedRow(long id);
  void patch(long id, Map<String, Object> request);
  void audit( SessionPrincipal actor, String action, long member, String reason, String summary);
  void ensureDirection(long direction);
  static void fields(Map<String, Object> body, Set<String> allowed) { MemberServiceImpl.fields(body, allowed); }
  static String required(Map<String, Object> body, String field, int max) { return MemberServiceImpl.required(body, field, max); }
  static long parseId(String value) { return MemberServiceImpl.parseId(value); }
  static ApiException bad() { return MemberServiceImpl.bad(); }
  static void validateProfile(Map<String, Object> request) { MemberServiceImpl.validateProfile(request); }
  IdempotencyService.StoredResponse adminAction( long member, Map<String, Object> request, SessionPrincipal actor, String key, String path, java.util.function.Supplier<Map<String, Object>> operation);
  Map<String, Object> correct(long id, Map<String, Object> request, SessionPrincipal actor);
  Map<String, Object> reset(long id, Map<String, Object> request, SessionPrincipal actor);
  void revoke(long user, String reason);
  Map<String, Object> restore(long id, Map<String, Object> request, SessionPrincipal actor);
  Map<String, Object> addPoints(long id, Map<String, Object> request, SessionPrincipal actor);
}
