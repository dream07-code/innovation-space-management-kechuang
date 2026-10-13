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

public interface MembershipRemovalService {
  int removeDueMembers();
  boolean removeDueMember(long memberId);
}
