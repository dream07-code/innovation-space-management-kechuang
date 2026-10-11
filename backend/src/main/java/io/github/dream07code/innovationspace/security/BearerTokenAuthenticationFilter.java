package io.github.dream07code.innovationspace.security;

import io.github.dream07code.innovationspace.common.exception.ApiException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

public class BearerTokenAuthenticationFilter extends OncePerRequestFilter {
  private final SessionService sessions;
  private final ObjectProvider<RequestMappingHandlerMapping> mappings;

  public BearerTokenAuthenticationFilter(
      SessionService sessions, ObjectProvider<RequestMappingHandlerMapping> mappings) {
    this.sessions = sessions;
    this.mappings = mappings;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    boolean authEndpoint = path.equals("/api/v1/auth/login") || path.equals("/api/v1/auth/refresh");
    boolean knownProtected = false;
    if (path.startsWith("/api/v1/")) {
      try {
        knownProtected = mappings.getObject().getHandler(request) != null;
      } catch (Exception routeError) {
        chain.doFilter(request, response);
        return;
      }
    }
    if (!authEndpoint && !knownProtected) {
      chain.doFilter(request, response);
      return;
    }
    try {
      sessions.requireSchema();
    } catch (io.github.dream07code.innovationspace.config.SchemaUnavailableException ex) {
      write(response, 503, "SERVICE_UNAVAILABLE", "Service unavailable");
      return;
    }
    if (authEndpoint) {
      chain.doFilter(request, response);
      return;
    }
    String header = request.getHeader("Authorization");
    if (header != null && !header.startsWith("Bearer ")) {
      write(response, 401, "UNAUTHORIZED", "Authentication required");
      return;
    }
    try {
      if (header != null) {
        try {
          var principal = sessions.authenticateAccess(header.substring(7));
          SecurityContextHolder.getContext()
              .setAuthentication(
                  new UsernamePasswordAuthenticationToken(principal, null, java.util.List.of()));
          boolean allowed =
              path.equals("/api/v1/auth/me")
                  || path.equals("/api/v1/auth/password")
                  || path.equals("/api/v1/auth/logout")
                  || !principal.mustChangePassword();
          if (!allowed) {
            write(response, 403, "PASSWORD_CHANGE_REQUIRED", "Password change required");
            return;
          }
        } catch (ApiException ex) {
          if (knownProtected) {
            write(response, ex.status(), ex.code().name(), ex.code().message());
            return;
          }
        } catch (io.github.dream07code.innovationspace.config.SchemaUnavailableException ex) {
          write(response, 503, "SERVICE_UNAVAILABLE", "Service unavailable");
          return;
        }
      } else if (knownProtected && !authEndpoint) {
        write(response, 401, "UNAUTHORIZED", "Authentication required");
        return;
      }
      chain.doFilter(request, response);
    } finally {
      SecurityContextHolder.clearContext();
    }
  }

  private static void write(HttpServletResponse response, int status, String code, String message)
      throws IOException {
    response.setStatus(status);
    response.setContentType("application/json");
    response.setHeader("Cache-Control", "no-store");
    response
        .getWriter()
        .write("{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"data\":null}");
  }
}
