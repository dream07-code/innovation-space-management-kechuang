package io.github.dream07code.innovationspace.service;

import io.github.dream07code.innovationspace.security.IdempotencyService;
import io.github.dream07code.innovationspace.security.SessionPrincipal;
import java.util.Map;

/**
 * AI 助教的多会话管理。
 *
 * <p>会话与消息都按学生（member）归属，接口只允许操作本人的会话；访问别人的会话一律 404，
 * 不区分「不存在」与「不属于你」，避免用 id 探测他人会话。
 *
 * <p>没有学生档案的账号（学院管理员）不能开会话：会话表以 member_id 为外键，
 * 这里直接 403，而不是写一条没有归属的会话。
 */
public interface TutorConversationService {

  /** 新建会话；problemId 可空，空表示纯知识点问答。走幂等键。 */
  IdempotencyService.StoredResponse create(
      Map<String, Object> request, SessionPrincipal actor, String key);

  /** 本人会话列表，最近更新在前。 */
  Map<String, Object> list(SessionPrincipal actor, int page, int size);

  /** 一个会话的元信息与消息（按时间正序）。 */
  Map<String, Object> messages(long conversationId, SessionPrincipal actor);

  /** 重命名会话。 */
  Map<String, Object> rename(
      long conversationId, Map<String, Object> request, SessionPrincipal actor);

  /** 删除会话，消息级联删除。走幂等键。 */
  IdempotencyService.StoredResponse delete(
      long conversationId, SessionPrincipal actor, String key);
}
