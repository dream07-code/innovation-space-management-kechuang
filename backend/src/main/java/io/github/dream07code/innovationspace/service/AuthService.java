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

public interface AuthService {
  SessionService.IssuedSession login(String account, String password);
  SessionService.RefreshResult refresh(String token);
  void changePassword(SessionPrincipal principal, String oldPassword, String newPassword);
  java.util.Map<String, Object> identity(long userId);
  java.util.Map<String, Object> me(SessionPrincipal p);
}
