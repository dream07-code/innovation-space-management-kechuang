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

public interface ReportService {
  IdempotencyService.StoredResponse create( Map<String, Object> request, SessionPrincipal principal, String key, ReportType type);
  Map<String, Object> get(long id, SessionPrincipal principal);
  Map<String, Object> list( ReportType type, SessionPrincipal principal, int page, int size, Long memberId, Long directionId, String fromDate, String toDate, String status, String scoreStatus);
  IdempotencyService.StoredResponse submit( long id, long version, SessionPrincipal principal, String key);
  Map<String, Object> update( long id, Map<String, Object> request, SessionPrincipal principal, ReportType type);
  Map<String, Object> manualScore( long id, Map<String, Object> request, SessionPrincipal principal, ReportType type);
  Map<String, Object> response(long id);
  static ApiException bad() {
    return new ApiException(400, ErrorCode.INVALID_REQUEST);
  }
}
