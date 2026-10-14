package io.github.dream07code.innovationspace.security;

import java.util.List;

public record SessionPrincipal(
    long userId,
    Long memberId,
    String role,
    List<Long> managedDirectionIds,
    boolean mustChangePassword,
    long authRevision,
    long sessionId) {}
