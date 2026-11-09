package io.github.dream07code.innovationspace.tutor;

import java.time.Instant;

/** 一次助教会话。problem_id 为空表示纯知识点问答，不绑定题目。 */
public record TutorConversation(
    long id, long memberId, String title, Long problemId, Instant createdAt, Instant updatedAt) {}
