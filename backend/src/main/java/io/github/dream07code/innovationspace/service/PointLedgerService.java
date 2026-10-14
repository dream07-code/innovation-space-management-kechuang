package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import io.github.dream07code.innovationspace.security.SessionService;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import java.time.Instant;
import java.util.*;
import io.github.dream07code.innovationspace.assessment.*;
import io.github.dream07code.innovationspace.report.*;
import io.github.dream07code.innovationspace.dashboard.*;
import io.github.dream07code.innovationspace.member.*;
import io.github.dream07code.innovationspace.membership.*;
import io.github.dream07code.innovationspace.points.*;

public interface PointLedgerService {
  long total(long member);
  long total(long member, MonthFilter month);
  Map<String, Object> summary(long member, String monthValue);
  Map<String, Object> ledger( SessionPrincipal principal, Long requestedMember, String monthValue, int page, int size);
  void appendIfAbsent( long member, long report, long version, int delta, String source, String eventKey, Instant effective);
  int reverseNonZeroEntries(long memberId, String ruleVersion, Instant recordedAt);
  int sourceNet(long memberId, String source);
  int sourceNet(long memberId, Long reportId, String source);
}
