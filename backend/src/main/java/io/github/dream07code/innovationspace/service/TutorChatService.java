package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/**
 * 通用 AI 助教对话：不绑定某次提交，学生只问知识点也能用。
 *
 * <p>上下文只包含学生当前选中的题目（可空），由服务端按 problemId 取题库并做可见性校验；
 * 前端只能传问题与历史对话。不写任何表，历史由前端保存，因此不需要数据库结构变更。
 */
public interface TutorChatService {
  Map<String, Object> chat(Map<String, Object> request, SessionPrincipal actor);
}
