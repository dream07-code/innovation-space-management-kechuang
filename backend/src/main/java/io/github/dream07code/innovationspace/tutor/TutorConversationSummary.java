package io.github.dream07code.innovationspace.tutor;

/**
 * 会话列表/详情需要的附加信息。
 *
 * <p>关联题目标题与消息条数用同一条 SQL 带出来，避免列表里每行再查一次库。
 */
public record TutorConversationSummary(
    TutorConversation conversation, String problemTitle, long messageCount) {}
