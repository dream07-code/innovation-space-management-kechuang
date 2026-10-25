package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.innovationspace.common.api.ErrorCode;
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
  static void fields(Map<String, Object> body, Set<String> allowed) {
    if (body == null || !allowed.containsAll(body.keySet())) throw bad();
  }
  static String required(Map<String, Object> body, String field, int max) {
    Object value = body.get(field);
    if (!(value instanceof String text) || text.isBlank() || text.length() > max) throw bad();
    return text;
  }
  static long parseId(String value) {
    if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw bad();
    try {
      long id = Long.parseLong(value);
      if (id <= 0) throw bad();
      return id;
    } catch (NumberFormatException error) {
      throw bad();
    }
  }
  static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
  static void validateProfile(Map<String, Object> request) {
    for (String field : new String[] {"major", "className", "contact"})
      if (request.containsKey(field)
          && request.get(field) != null
          && (!(request.get(field) instanceof String text) || text.length() > 100)) throw bad();
    if (request.containsKey("grade") && request.get("grade") != null) {
      if (!(request.get("grade") instanceof Number number)
          || number.doubleValue() != number.intValue()
          || number.intValue() < 1900
          || number.intValue() > 2200) throw bad();
    }
    if (request.containsKey("name")) required(request, "name", 64);
  }
  IdempotencyService.StoredResponse adminAction( long member, Map<String, Object> request, SessionPrincipal actor, String key, String path, java.util.function.Supplier<Map<String, Object>> operation);
  Map<String, Object> correct(long id, Map<String, Object> request, SessionPrincipal actor);
  Map<String, Object> reset(long id, Map<String, Object> request, SessionPrincipal actor);
  void revoke(long user, String reason);
  Map<String, Object> restore(long id, Map<String, Object> request, SessionPrincipal actor);
  Map<String, Object> addPoints(long id, Map<String, Object> request, SessionPrincipal actor);
  Map<String, Object> delete(long id, Map<String, Object> request, SessionPrincipal actor);
  Map<String, Object> resetPoints(long id, Map<String, Object> request, SessionPrincipal actor);
}
