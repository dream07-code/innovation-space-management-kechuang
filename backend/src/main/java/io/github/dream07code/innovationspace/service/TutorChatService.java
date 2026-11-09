package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/**
 * 通用 AI 助教对话：不绑定某次提交，学生只问知识点也能用。
 *
 * <p>上下文只包含会话关联的题目（可空），由服务端按 problemId 取题库并做可见性校验；
 * 前端只传问题与 conversationId。conversationId 为空时自动新建会话，
 * 历史由服务端按会话从 tutor_message 取，前端不再上传历史。
 */
public interface TutorChatService {
  Map<String, Object> chat(Map<String, Object> request, SessionPrincipal actor);
}
