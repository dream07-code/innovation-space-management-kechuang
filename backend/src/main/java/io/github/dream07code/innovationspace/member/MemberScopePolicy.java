package io.github.dream07code.innovationspace.member;

import io.github.dream07code.innovationspace.common.api.ErrorCode;
import io.github.dream07code.innovationspace.common.exception.ApiException;
import io.github.dream07code.innovationspace.security.SessionPrincipal;

public final class MemberScopePolicy {
  private MemberScopePolicy() {}

  public static void staff(SessionPrincipal p) {
    if (!"COLLEGE_ADMIN".equals(p.role()) && !"DIRECTION_LEADER".equals(p.role()))
      throw new ApiException(403, ErrorCode.FORBIDDEN);
  }

  public static void admin(SessionPrincipal p) {
    if (!"COLLEGE_ADMIN".equals(p.role())) throw new ApiException(403, ErrorCode.FORBIDDEN);
  }

  public static void direction(SessionPrincipal p, long direction) {
    staff(p);
    if (!"COLLEGE_ADMIN".equals(p.role()) && !p.managedDirectionIds().contains(direction))
      throw new ApiException(403, ErrorCode.FORBIDDEN);
  }
}
