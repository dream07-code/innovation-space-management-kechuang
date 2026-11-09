package io.github.dream07code.innovationspace.tutor;

import java.time.Instant;

/** 会话里的一条消息。role 只有 user / assistant 两种，由数据库 CHECK 约束兜底。 */
public record TutorMessage(long id, String role, String content, Instant createdAt) {}
