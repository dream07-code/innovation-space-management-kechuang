package io.github.dream07code.innovationspace.web;

import io.github.dream07code.innovationspace.common.api.ApiResponse;
import io.github.dream07code.innovationspace.security.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {
  public record LoginRequest(
      @NotBlank @Size(max = 64) String account, @NotBlank @Size(max = 128) String password) {
    @Override
    public String toString() {
      return "LoginRequest[redacted]";
    }
  }

  public record RefreshRequest(@NotBlank @Size(max = 4096) String refreshToken) {
    @Override
    public String toString() {
      return "RefreshRequest[redacted]";
    }
  }

  public record ChangePasswordRequest(
      @NotBlank @Size(max = 128) String oldPassword, @NotBlank String newPassword) {
    @Override
    public String toString() {
      return "ChangePasswordRequest[redacted]";
    }
  }

  public record Identity(
      long userId,
      Long memberId,
      String account,
      String displayName,
      String role,
      Long directionId,
      java.util.List<Long> managedDirectionIds) {}

  public record SessionResponse(
      String accessToken,
      String refreshToken,
      String tokenType,
      long expiresIn,
      Long refreshExpiresIn,
      boolean mustChangePassword,
      java.util.Map<String, Object> user) {}

  public record MeResponse(
      Identity user,
      boolean mustChangePassword,
      String accountStatus,
      String membershipStatus,
      java.util.Map<String, Object> profile) {}

  private final AuthService auth;
  private final SessionService sessions;
  private final RateLimitService rate;
  private final TokenService tokens;

  public AuthController(
      AuthService auth, SessionService sessions, RateLimitService rate, TokenService tokens) {
    this.auth = auth;
    this.sessions = sessions;
    this.rate = rate;
    this.tokens = tokens;
  }

  @PostMapping("/login")
  public ResponseEntity<ApiResponse<SessionResponse>> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    rate.check("login:" + http.getRemoteAddr() + ":" + tokens.digest(request.account()));
    var s = auth.login(request.account(), request.password());
    return noStore(
        ApiResponse.success(
            new SessionResponse(
                s.accessToken(),
                s.refreshToken(),
                "Bearer",
                s.expiresIn(),
                s.refreshExpiresIn(),
                s.mustChangePassword(),
                auth.identity(s.userId()))));
  }

  @PostMapping("/refresh")
  public ResponseEntity<ApiResponse<SessionResponse>> refresh(
      @Valid @RequestBody RefreshRequest request, HttpServletRequest http) {
    rate.check("refresh:" + http.getRemoteAddr() + ":" + tokens.digest(request.refreshToken()));
    var result = auth.refresh(request.refreshToken());
    if (!result.success())
      return ResponseEntity.status(401)
          .cacheControl(CacheControl.noStore())
          .body(new ApiResponse<>("REFRESH_TOKEN_INVALID", "Refresh token invalid", null));
    var s = result.session();
    return noStore(
        ApiResponse.success(
            new SessionResponse(
                s.accessToken(),
                s.refreshToken(),
                "Bearer",
                s.expiresIn(),
                s.refreshExpiresIn(),
                s.mustChangePassword(),
                auth.identity(s.userId()))));
  }

  @PostMapping("/logout")
  public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
    var token = bearer(request);
    if (token != null) sessions.revokeSession(token, "LOGOUT");
    return noStore(ApiResponse.success(null));
  }

  @GetMapping("/me")
  public ApiResponse<java.util.Map<String, Object>> me() {
    return ApiResponse.success(auth.me(principal()));
  }

  @PutMapping("/password")
  public ResponseEntity<ApiResponse<Void>> password(
      @Valid @RequestBody ChangePasswordRequest request, HttpServletRequest servlet) {
    rate.check("password:" + principal().userId());
    auth.changePassword(principal(), request.oldPassword(), request.newPassword());
    return noStore(ApiResponse.success(null));
  }

  private SessionPrincipal principal() {
    return (SessionPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
  }

  private static String bearer(HttpServletRequest r) {
    var h = r.getHeader("Authorization");
    return h != null && h.startsWith("Bearer ") ? h.substring(7) : null;
  }

  private static <T> ResponseEntity<ApiResponse<T>> noStore(ApiResponse<T> body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
